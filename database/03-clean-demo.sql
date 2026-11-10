-- =====================================================================
--  星嘻进销存 · 演示/交付前数据清理
--  用途：
--    - 清理验收脚本和页面试用过程中产生的业务单据、待办、审批、库存流水
--    - 保留组织、用户、角色、菜单、供应商、物料、仓库等基础资料
--    - 恢复 02-seed.sql 中的期初库存
--  注意：
--    - 会删除业务单据与库存流水，正式业务环境不要直接执行
-- =====================================================================

USE scm;

SET FOREIGN_KEY_CHECKS = 0;

DELETE FROM biz_invoice_line;
DELETE FROM biz_invoice;
DELETE FROM biz_recv_line;
DELETE FROM biz_recv;
DELETE FROM biz_po_line;
DELETE FROM biz_po;
DELETE FROM biz_pr_line;
DELETE FROM biz_pr;
DELETE FROM biz_out_line;
DELETE FROM biz_out;
DELETE FROM biz_out_req_line;
DELETE FROM biz_out_req;
DELETE FROM biz_out_req_revision_line;
DELETE FROM biz_out_req_revision;
DELETE FROM biz_return_line;
DELETE FROM biz_return;
DELETE FROM biz_loan_line;
DELETE FROM biz_loan;

DELETE FROM fin_period_close;
DELETE FROM doc_relation;
DELETE FROM wf_record;
DELETE FROM wf_instance;
DELETE FROM wf_cancel_request;
DELETE FROM notif;
DELETE FROM sys_log;
DELETE FROM inv_transaction;
DELETE FROM inv_balance;
DELETE FROM sys_user_role
WHERE user_id IN (
  SELECT id FROM sys_user
  WHERE username REGEXP '^(locktest|verify)_'
);
DELETE FROM sys_user
WHERE username REGEXP '^(locktest|verify)_';

ALTER TABLE biz_invoice_line AUTO_INCREMENT = 1;
ALTER TABLE biz_invoice AUTO_INCREMENT = 1;
ALTER TABLE biz_recv_line AUTO_INCREMENT = 1;
ALTER TABLE biz_recv AUTO_INCREMENT = 1;
ALTER TABLE biz_po_line AUTO_INCREMENT = 1;
ALTER TABLE biz_po AUTO_INCREMENT = 1;
ALTER TABLE biz_pr_line AUTO_INCREMENT = 1;
ALTER TABLE biz_pr AUTO_INCREMENT = 1;
ALTER TABLE biz_out_line AUTO_INCREMENT = 1;
ALTER TABLE biz_out AUTO_INCREMENT = 1;
ALTER TABLE biz_out_req_line AUTO_INCREMENT = 1;
ALTER TABLE biz_out_req AUTO_INCREMENT = 1;
ALTER TABLE doc_relation AUTO_INCREMENT = 1;
ALTER TABLE wf_record AUTO_INCREMENT = 1;
ALTER TABLE wf_instance AUTO_INCREMENT = 1;
ALTER TABLE wf_cancel_request AUTO_INCREMENT = 1;
ALTER TABLE notif AUTO_INCREMENT = 1;
ALTER TABLE sys_log AUTO_INCREMENT = 1;
ALTER TABLE inv_transaction AUTO_INCREMENT = 1;
ALTER TABLE inv_balance AUTO_INCREMENT = 1;

UPDATE sys_doc_rule
SET current_no = 0,
    last_reset = NULL
WHERE doc_type IN ('PR', 'PO', 'RECV', 'INV', 'OREQ', 'OREQ_REV', 'OUT', 'RETURN', 'LOAN', 'TRANSFER');

DELETE FROM sys_doc_sequence
WHERE doc_type IN ('PR', 'PO', 'RECV', 'INV', 'OREQ', 'OREQ_REV', 'OUT', 'RETURN', 'LOAN', 'TRANSFER');

UPDATE sys_user
SET fail_count = 0,
    locked_until = NULL
WHERE deleted = 0;

INSERT INTO inv_balance (material_id, warehouse_id, qty, amount, avg_price) VALUES
(1, 1, 100, 1000.00, 10.0000),
(2, 1, 50,  2200.00, 44.0000);

INSERT INTO inv_transaction (trans_no, material_id, warehouse_id, trans_type, biz_doc_type, biz_doc_no,
                             qty, unit_price, amount, bal_qty, bal_amount, bal_avg_price, remark)
VALUES
('TR-INIT-001', 1, 1, 'INIT', 'INIT', 'INIT-2026', 100, 10.0000, 1000.00, 100, 1000.00, 10.0000, '期初余额'),
('TR-INIT-002', 2, 1, 'INIT', 'INIT', 'INIT-2026', 50,  44.0000, 2200.00, 50,  2200.00, 44.0000, '期初余额');

SET FOREIGN_KEY_CHECKS = 1;
