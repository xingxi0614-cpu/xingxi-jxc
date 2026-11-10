-- 存货核算回滚:按公司 → 按仓库(配合 scripts/cost-cutover.sql;必须先停后端、先全库备份)
--   mysql --default-character-set=utf8mb4 scm < scripts/cost-rollback.sql
--
-- 两种情况自动区分:
--   A. 切换后还没有任何按公司记的流水:把各仓金额/单价原样恢复成切换前快照,零损失。
--   B. 切换后已经有单据过账:不恢复快照(会和新流水对不上),各仓保留当前"按数量分摊"的金额继续按仓核算;
--      公司总额不变,只是各仓起点 = 切换时的分摊额。按公司期间的流水原样保留。
-- 两种情况都不删除流水、不动业务单据;回滚后引擎立即按仓库核算(inv_cost_cutover 最新行 = WH)。
-- 回滚时记下各仓"余额 − 流水累计"基点(inv_cost_baseline),再次切换时闸门据此核对。

SET NAMES utf8mb4;

DROP PROCEDURE IF EXISTS cost_rollback_proc;
DELIMITER //
CREATE PROCEDURE cost_rollback_proc()
BEGIN
  DECLARE v_scope VARCHAR(10);
  DECLARE v_cut BIGINT;
  DECLARE v_flow_id BIGINT;
  DECLARE v_flows INT DEFAULT 0;
  DECLARE v_bad INT DEFAULT 0;
  DECLARE v_rb BIGINT;
  DECLARE EXIT HANDLER FOR SQLEXCEPTION
  BEGIN
    ROLLBACK;
    RESIGNAL;
  END;

  SELECT scope_after INTO v_scope FROM inv_cost_cutover ORDER BY id DESC LIMIT 1;
  IF v_scope IS NULL OR v_scope <> 'ORG' THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '当前不是按公司核算,无需回滚';
  END IF;
  SELECT id, flow_id_at INTO v_cut, v_flow_id FROM inv_cost_cutover
  WHERE action = 'CUTOVER' ORDER BY id DESC LIMIT 1;

  START TRANSACTION;
  SELECT COUNT(*) INTO v_bad FROM inv_cost_lock FOR UPDATE;

  SELECT COUNT(*) INTO v_flows FROM inv_transaction
  WHERE id > v_flow_id;   -- 按流水编号判断,不按时间(数据库与应用时区可能不同,2026-10-15 E2E 实踩)

  IF v_flows = 0 THEN
    UPDATE inv_balance b
    JOIN inv_cost_cutover_snapshot s ON s.cutover_id = v_cut AND s.warehouse_id = b.warehouse_id AND s.material_id = b.material_id
    SET b.amount = s.old_amount, b.avg_price = s.old_avg_price, b.version = b.version + 1;
    INSERT INTO inv_cost_cutover (action, scope_after, operator, remark, flow_id_at)
    SELECT 'ROLLBACK', 'WH', CURRENT_USER(), CONCAT('回滚到按仓核算:切换后无新流水,已按快照 #', v_cut, ' 原样恢复各仓金额'), COALESCE(MAX(id), 0)
    FROM inv_transaction;
  ELSE
    INSERT INTO inv_cost_cutover (action, scope_after, operator, remark, flow_id_at)
    SELECT 'ROLLBACK', 'WH', CURRENT_USER(), CONCAT('回滚到按仓核算:切换后已有 ', v_flows, ' 笔新流水,各仓保留按数量分摊后的金额继续核算'), COALESCE(MAX(id), 0)
    FROM inv_transaction;
  END IF;
  SET v_rb = LAST_INSERT_ID();

  -- 基点(计算审查 U38,2026-10-18):记下每个仓库×物料"余额 − 流水累计"。按公司期间的分摊不写流水,
  -- 不记这一笔,下次再切换时闸门"流水累计 = 余额"永远对不上。下次切换按"流水累计 + 基点 = 余额"核对。
  INSERT INTO inv_cost_baseline (rollback_id, warehouse_id, material_id, qty_drift, amount_drift)
  SELECT v_rb, b.warehouse_id, b.material_id, b.qty - IFNULL(f.fq, 0), b.amount - IFNULL(f.fa, 0)
  FROM inv_balance b
  LEFT JOIN (SELECT material_id, warehouse_id, SUM(qty) fq, SUM(amount) fa
             FROM inv_transaction WHERE trans_type <> 'ADJUST_EXPENSE'
             GROUP BY material_id, warehouse_id) f
    ON f.material_id = b.material_id AND f.warehouse_id = b.warehouse_id
  WHERE ABS(b.qty - IFNULL(f.fq, 0)) > 0.00005 OR ABS(b.amount - IFNULL(f.fa, 0)) > 0.005;

  -- 自检:每家公司各仓金额合计 = 公司成本账(情况 B)或 = 切换前快照(情况 A)
  SELECT COUNT(*) INTO v_bad FROM (
    SELECT w.org_id, SUM(b.amount) wa
    FROM inv_balance b JOIN md_warehouse w ON w.id = b.warehouse_id
    GROUP BY w.org_id
    HAVING ABS(SUM(b.amount) - IFNULL(CASE WHEN v_flows = 0
        THEN (SELECT SUM(s.old_amount) FROM inv_cost_cutover_snapshot s WHERE s.cutover_id = v_cut AND s.org_id = w.org_id)
        ELSE (SELECT SUM(p.amount) FROM inv_cost_pool p WHERE p.org_id = w.org_id) END, 0)) > 0.001
  ) x;
  IF v_bad > 0 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '自检不过:各仓金额合计与公司账/快照不一致,已整体回滚';
  END IF;

  COMMIT;
  SELECT action, scope_after, remark, created_at FROM inv_cost_cutover ORDER BY id DESC LIMIT 1;
END //
DELIMITER ;

CALL cost_rollback_proc();
DROP PROCEDURE IF EXISTS cost_rollback_proc;
