-- 存货核算切换:按仓库 → 按公司
--
-- 用法(必须先停后端、先全库备份):Docker 安装时
--   docker compose stop backend
--   docker compose exec -T db sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" mysql --default-character-set=utf8mb4 -u scm scm' < scripts/cost-cutover.sql
--   docker compose start backend
--
-- 做什么:
--   ① 闸门:当前必须是按仓核算;各仓"流水累计 = 库存余额"(演示账套除外);没有"数量 0 金额非 0 / 负数"的余额行
--   ② 记切换记录 inv_cost_cutover(CUTOVER, ORG)
--   ③ 存快照 inv_cost_cutover_snapshot:每个仓库×物料切换前的数量/金额/单价 + 切换后的参考分摊额 → 对照表凭据
--   ④ 重建公司成本池 inv_cost_pool:数量 = Σ各仓数量,金额 = Σ各仓金额(公司库存总额一分不变)
--   ⑤ 各仓 inv_balance 金额改为按数量占比分摊的参考额(尾差按数量降序/仓id升序非负分摊,与 CostMath.allocate 同算法),单价 = 公司单价
--   ⑥ 自检:每家公司 Σ池金额 = Σ切换前金额 = Σ各仓新金额;Σ池数量 = Σ各仓数量。任何一条不过整体回滚
-- 不产生任何出入库流水,不动任何业务单据,已结账月份不受影响。
-- 回滚:scripts/cost-rollback.sql

SET NAMES utf8mb4;
-- 单价/分摊的中间除法保留足够位数,避免 MySQL 默认 4 位小数"先截断再四舍五入"与 Java 结果差一分
SET SESSION div_precision_increment = 30;

DROP PROCEDURE IF EXISTS cost_cutover_proc;
DELIMITER //
CREATE PROCEDURE cost_cutover_proc()
BEGIN
  DECLARE v_scope VARCHAR(10);
  DECLARE v_bad INT DEFAULT 0;
  DECLARE v_demo BIGINT DEFAULT NULL;
  DECLARE v_cut BIGINT;
  DECLARE v_rb BIGINT DEFAULT NULL;
  DECLARE EXIT HANDLER FOR SQLEXCEPTION
  BEGIN
    ROLLBACK;
    RESIGNAL;
  END;

  SELECT scope_after INTO v_scope FROM inv_cost_cutover ORDER BY id DESC LIMIT 1;
  IF v_scope = 'ORG' THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '已经是按公司核算,无需重复切换';
  END IF;

  SELECT CAST(param_value AS UNSIGNED) INTO v_demo FROM sys_param WHERE param_key = 'demo.org.id' LIMIT 1;

  START TRANSACTION;

  -- 锁住全部公司成本锁,确保切换期间没有任何过账(正常应已停后端,这里再兜一层)
  INSERT IGNORE INTO inv_cost_lock (org_id) SELECT id FROM sys_org;
  SELECT COUNT(*) INTO v_bad FROM inv_cost_lock FOR UPDATE;

  -- ① 闸门 a:流水累计 (+ 上次回滚记下的基点) = 余额(剔除只进损益的 ADJUST_EXPENSE;演示账套的样例余额是直接写入的,不在闸门内)
  --    回滚过再切换(计算审查 U38,2026-10-18):按公司期间的分摊不写流水,回滚时已把各仓"余额 − 流水累计"记成基点,
  --    这里加上基点再比——回滚之后新出的错账照样拦得住,不需要跳过闸门。
  --    仅本地 E2E 的种子库首次切换(余额无流水)可 SET @skip_flow_gate = 1 跳过;生产不许设
  SELECT id INTO v_rb FROM inv_cost_cutover WHERE action = 'ROLLBACK' ORDER BY id DESC LIMIT 1;
  SET v_bad = 0;
  IF IFNULL(@skip_flow_gate, 0) = 0 THEN
  SELECT COUNT(*) INTO v_bad
  FROM inv_balance b
  JOIN md_warehouse w ON w.id = b.warehouse_id
  LEFT JOIN (SELECT material_id, warehouse_id, SUM(qty) fq, SUM(amount) fa
             FROM inv_transaction WHERE trans_type <> 'ADJUST_EXPENSE'
             GROUP BY material_id, warehouse_id) f
    ON f.material_id = b.material_id AND f.warehouse_id = b.warehouse_id
  LEFT JOIN inv_cost_baseline bl
    ON bl.rollback_id = v_rb AND bl.material_id = b.material_id AND bl.warehouse_id = b.warehouse_id
  WHERE (v_demo IS NULL OR w.org_id <> v_demo)
    AND (ABS(IFNULL(f.fq, 0) + IFNULL(bl.qty_drift, 0) - b.qty) > 0.00005
      OR ABS(IFNULL(f.fa, 0) + IFNULL(bl.amount_drift, 0) - b.amount) > 0.005);
  END IF;
  IF v_bad > 0 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '闸门不过:有仓库的流水累计与库存余额对不上,先查清再切换';
  END IF;

  -- ① 闸门 b:余额本身合法
  SELECT COUNT(*) INTO v_bad FROM inv_balance
  WHERE qty < 0 OR amount < 0 OR (qty = 0 AND amount <> 0);
  IF v_bad > 0 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '闸门不过:存在负库存/负金额/数量为0金额不为0的余额行';
  END IF;

  -- ② 切换记录
  INSERT INTO inv_cost_cutover (action, scope_after, operator, remark, flow_id_at)
  SELECT 'CUTOVER', 'ORG', CURRENT_USER(), '按仓库核算 → 按公司核算', COALESCE(MAX(id), 0)
  FROM inv_transaction;
  SET v_cut = LAST_INSERT_ID();

  -- ③ 快照 + 参考分摊额
  INSERT INTO inv_cost_cutover_snapshot
    (cutover_id, org_id, warehouse_id, material_id, qty, old_amount, old_avg_price,
     pool_qty, pool_amount, pool_price, new_amount, shift_amount, suspect)
  WITH bb AS (
    SELECT w.org_id, b.warehouse_id, b.material_id, b.qty, b.amount, b.avg_price,
           CASE WHEN b.qty > 0 THEN b.amount / b.qty END wp
    FROM inv_balance b JOIN md_warehouse w ON w.id = b.warehouse_id
  ),
  pp AS (
    SELECT org_id, material_id, SUM(qty) pq, SUM(amount) pa,
           SUM(CASE WHEN qty > 0 THEN qty ELSE 0 END) pos, MAX(wp) maxwp,
           CASE WHEN SUM(qty) > 0 THEN ROUND(SUM(amount) / SUM(qty), 2) ELSE 0 END pprice
    FROM bb GROUP BY org_id, material_id
  ),
  aa AS (
    SELECT bb.*, pp.pq, pp.pa, pp.pos, pp.pprice, pp.maxwp,
           CASE WHEN bb.qty > 0 AND pp.pos > 0 THEN ROUND(pp.pa * bb.qty / pp.pos, 2) ELSE 0 END alloc0,
           ROW_NUMBER() OVER (PARTITION BY bb.org_id, bb.material_id
                              ORDER BY (bb.qty > 0) DESC, bb.qty DESC, bb.warehouse_id) rn
    FROM bb JOIN pp ON pp.org_id = bb.org_id AND pp.material_id = bb.material_id
  ),
  ss AS (
    SELECT aa.*,
           SUM(alloc0) OVER (PARTITION BY org_id, material_id) sa,
           COALESCE(SUM(alloc0) OVER (PARTITION BY org_id, material_id ORDER BY rn
                                    ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING), 0) prior_alloc
    FROM aa
  ),
  final_alloc AS (
    SELECT ss.*,
           CASE WHEN pa >= sa THEN alloc0 + CASE WHEN rn = 1 AND pos > 0 THEN pa - sa ELSE 0 END
                ELSE alloc0 - LEAST(alloc0, GREATEST(sa - pa - prior_alloc, 0)) END allocated
    FROM ss
  )
  SELECT v_cut, org_id, warehouse_id, material_id, qty, amount, avg_price,
         pq, pa, pprice, allocated, allocated - amount,
         CASE WHEN wp IS NOT NULL AND maxwp > 0 AND wp < maxwp * 0.2 THEN 1 ELSE 0 END
  FROM final_alloc;

  -- ④ 公司成本池(先清掉回滚后残留的旧池)
  DELETE FROM inv_cost_pool;
  INSERT INTO inv_cost_pool (org_id, material_id, qty, amount, avg_price, version)
  SELECT org_id, material_id, MAX(pool_qty), MAX(pool_amount), MAX(pool_price), 0
  FROM inv_cost_cutover_snapshot WHERE cutover_id = v_cut
  GROUP BY org_id, material_id;

  -- ⑤ 各仓改为参考分摊额 + 公司单价
  UPDATE inv_balance b
  JOIN inv_cost_cutover_snapshot s ON s.cutover_id = v_cut AND s.warehouse_id = b.warehouse_id AND s.material_id = b.material_id
  SET b.amount = s.new_amount,
      b.avg_price = CASE WHEN b.qty > 0 THEN s.pool_price ELSE 0 END,
      b.version = b.version + 1;

  -- ⑥ 自检
  SELECT COUNT(*) INTO v_bad FROM (
    SELECT s.org_id
    FROM inv_cost_cutover_snapshot s
    WHERE s.cutover_id = v_cut
    GROUP BY s.org_id
    HAVING ABS(SUM(s.old_amount) - SUM(s.new_amount)) > 0.001
        OR ABS(SUM(s.old_amount) - (SELECT SUM(p.amount) FROM inv_cost_pool p WHERE p.org_id = s.org_id)) > 0.001
        OR ABS(SUM(s.qty) - (SELECT SUM(p.qty) FROM inv_cost_pool p WHERE p.org_id = s.org_id)) > 0.00005
  ) x;
  IF v_bad > 0 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '自检不过:公司金额或数量与切换前不一致,已整体回滚';
  END IF;
  SELECT COUNT(*) INTO v_bad FROM inv_balance WHERE amount < 0;
  IF v_bad > 0 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '自检不过:出现负的仓库参考金额,已整体回滚';
  END IF;

  COMMIT;

  -- 结果汇总(每家公司一行):切换前后总额、仓间挪动(单边)、疑似错价行数
  SELECT o.name AS 公司,
         COUNT(DISTINCT s.material_id) AS 物料种数,
         SUM(s.old_amount) AS 切换前库存总额,
         (SELECT SUM(p.amount) FROM inv_cost_pool p WHERE p.org_id = s.org_id) AS 公司成本账总额,
         SUM(s.new_amount) - SUM(s.old_amount) AS 总额差,
         SUM(GREATEST(s.shift_amount, 0)) AS 仓间挪动_单边,
         SUM(s.suspect) AS 疑似错价行
  FROM inv_cost_cutover_snapshot s JOIN sys_org o ON o.id = s.org_id
  WHERE s.cutover_id = v_cut
  GROUP BY s.org_id, o.name
  ORDER BY 切换前库存总额 DESC;
END //
DELIMITER ;

CALL cost_cutover_proc();
DROP PROCEDURE IF EXISTS cost_cutover_proc;
