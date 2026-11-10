-- =====================================================================
--  星嘻进销存 · 数据库表结构(全量)
--  数据库:MySQL 8.0+   字符集:utf8mb4 / utf8mb4_unicode_ci
--  用法:全新安装时先执行本文件,再执行 02-seed.sql(示例数据与系统配置)。
--  以后的结构变更放 migrations/ 目录,见该目录 README。
-- =====================================================================
SET NAMES utf8mb4;

/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
/*!40101 SET @OLD_CHARACTER_SET_RESULTS=@@CHARACTER_SET_RESULTS */;
/*!40101 SET @OLD_COLLATION_CONNECTION=@@COLLATION_CONNECTION */;
/*!50503 SET NAMES utf8mb4 */;
/*!40103 SET @OLD_TIME_ZONE=@@TIME_ZONE */;
/*!40103 SET TIME_ZONE='+00:00' */;
/*!40014 SET @OLD_UNIQUE_CHECKS=@@UNIQUE_CHECKS, UNIQUE_CHECKS=0 */;
/*!40014 SET @OLD_FOREIGN_KEY_CHECKS=@@FOREIGN_KEY_CHECKS, FOREIGN_KEY_CHECKS=0 */;
/*!40101 SET @OLD_SQL_MODE=@@SQL_MODE, SQL_MODE='NO_AUTO_VALUE_ON_ZERO' */;
/*!40111 SET @OLD_SQL_NOTES=@@SQL_NOTES, SQL_NOTES=0 */;
DROP TABLE IF EXISTS `biz_attachment`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `biz_attachment` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `biz_type` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '单据类型 PO/RECV/INV/OREQ/OUT/RETURN/LOAN/TRANSFER/STOCKTAKE',
  `biz_id` bigint NOT NULL COMMENT '单据 id',
  `org_id` bigint NOT NULL COMMENT '单据所属账套(权限校验用)',
  `file_name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '原始文件名',
  `size_bytes` bigint NOT NULL DEFAULT '0',
  `mime` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `cos_key` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'COS 对象键 attachments/{bizType}/{bizId}/{uuid}-文件名',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `created_by` bigint DEFAULT NULL,
  `updated_by` bigint DEFAULT NULL,
  `deleted` tinyint NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_att_biz` (`biz_type`,`biz_id`,`deleted`),
  KEY `idx_att_org` (`org_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='单据附件(文件在COS)';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `biz_invoice`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `biz_invoice` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `doc_no` varchar(40) COLLATE utf8mb4_unicode_ci NOT NULL,
  `invoice_no` varchar(80) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '发票号码（供应商开具的）',
  `supplier_id` bigint NOT NULL,
  `org_id` bigint NOT NULL,
  `invoice_date` date NOT NULL,
  `total_qty` decimal(18,4) NOT NULL DEFAULT '0.0000',
  `net_amt` decimal(18,2) NOT NULL DEFAULT '0.00',
  `tax_amt` decimal(18,2) NOT NULL DEFAULT '0.00',
  `total_amt` decimal(18,2) NOT NULL DEFAULT '0.00',
  `adjust_amt` decimal(18,2) NOT NULL DEFAULT '0.00' COMMENT '差额调整',
  `status` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/PENDING/APPROVED/MATCHED/REJECTED',
  `remark` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `created_by` bigint DEFAULT NULL,
  `updated_by` bigint DEFAULT NULL,
  `deleted` tinyint NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_doc_no` (`doc_no`,`deleted`),
  KEY `idx_supplier` (`supplier_id`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='采购发票头';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `biz_invoice_line`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `biz_invoice_line` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `invoice_id` bigint NOT NULL,
  `line_no` int NOT NULL DEFAULT '1',
  `recv_line_id` bigint DEFAULT NULL COMMENT '关联的入库明细行（核销）',
  `material_id` bigint NOT NULL,
  `qty` decimal(18,4) NOT NULL,
  `unit_price` decimal(18,4) NOT NULL,
  `amount` decimal(18,2) NOT NULL,
  `recv_amt_used` decimal(18,2) DEFAULT NULL COMMENT '本行用掉的原入库不含税金额(审批时按剩余分,撤销后不再计入)',
  `tax_rate` decimal(5,2) NOT NULL DEFAULT '0.00',
  `tax_amount` decimal(18,2) NOT NULL DEFAULT '0.00',
  `calc_total_amt` decimal(18,2) DEFAULT NULL COMMENT '系统按数量×单价×(1+税率)算出的价税合计(留痕对照)',
  `amt_source` varchar(10) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'AUTO' COMMENT 'AUTO=系统算 MANUAL=手工改过',
  PRIMARY KEY (`id`),
  KEY `idx_invoice` (`invoice_id`),
  KEY `idx_recv_line` (`recv_line_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='采购发票明细';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `biz_loan`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `biz_loan` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `doc_no` varchar(40) COLLATE utf8mb4_unicode_ci NOT NULL,
  `warehouse_id` bigint NOT NULL,
  `dept_id` bigint NOT NULL,
  `org_id` bigint NOT NULL,
  `project_id` bigint DEFAULT NULL COMMENT '工程项目ID',
  `project_name` varchar(120) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '工程项目名称快照',
  `borrower` varchar(40) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '借料人',
  `borrower_dept` varchar(80) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '借料部门',
  `borrow_date` date NOT NULL,
  `expected_return_date` date DEFAULT NULL,
  `purpose` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '借料用途',
  `total_qty` decimal(18,4) NOT NULL DEFAULT '0.0000',
  `returned_qty` decimal(18,4) NOT NULL DEFAULT '0.0000',
  `total_amt` decimal(18,2) NOT NULL DEFAULT '0.00' COMMENT '按库存成本记录，仓管端不展示',
  `status` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'REGISTERED' COMMENT 'REGISTERED/CLOSED',
  `remark` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `created_by` bigint DEFAULT NULL COMMENT '创建人(原始提交人)',
  `deleted` tinyint NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_doc_no` (`doc_no`,`deleted`),
  KEY `idx_warehouse` (`warehouse_id`),
  KEY `idx_project` (`project_id`),
  KEY `idx_status` (`status`),
  KEY `idx_borrow_date` (`borrow_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='借料登记单头';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `biz_loan_line`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `biz_loan_line` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `loan_id` bigint NOT NULL,
  `line_no` int NOT NULL DEFAULT '1',
  `material_id` bigint NOT NULL,
  `qty` decimal(18,4) NOT NULL,
  `returned_qty` decimal(18,4) NOT NULL DEFAULT '0.0000',
  `unit_price` decimal(18,4) NOT NULL DEFAULT '0.0000' COMMENT '借出时加权平均单价，仓管端不展示',
  `amount` decimal(18,2) NOT NULL DEFAULT '0.00',
  `remark` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_loan` (`loan_id`),
  KEY `idx_material` (`material_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='借料登记明细';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `biz_loan_return`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `biz_loan_return` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `loan_id` bigint NOT NULL COMMENT '借料单',
  `loan_line_id` bigint NOT NULL COMMENT '借料行',
  `return_date` date NOT NULL COMMENT '归还日期(业务日期,报表按它归期)',
  `qty` decimal(18,4) NOT NULL COMMENT '本次归还数量',
  `amount` decimal(18,2) DEFAULT NULL COMMENT '本次回库金额',
  `inv_trans_id` bigint DEFAULT NULL COMMENT '对应的库存流水 inv_transaction.id',
  `remark` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '归还备注',
  `created_by` bigint DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_loan_return_trans` (`inv_trans_id`),
  KEY `idx_loan_return_loan` (`loan_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='借料归还记录';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `biz_out`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `biz_out` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `doc_no` varchar(40) COLLATE utf8mb4_unicode_ci NOT NULL,
  `source_req_id` bigint NOT NULL,
  `source_req_no` varchar(40) COLLATE utf8mb4_unicode_ci NOT NULL,
  `warehouse_id` bigint NOT NULL,
  `dept_id` bigint NOT NULL,
  `org_id` bigint NOT NULL,
  `project_id` bigint DEFAULT NULL COMMENT '工程项目ID',
  `project_name` varchar(120) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '工程项目名称快照',
  `construction_unit` varchar(120) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '施工单位',
  `out_type` varchar(40) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '出库类型',
  `business_category` varchar(40) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '业务分类',
  `material_receiver` varchar(40) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '材料领料人',
  `out_date` date NOT NULL,
  `total_qty` decimal(18,4) NOT NULL DEFAULT '0.0000',
  `total_amt` decimal(18,2) NOT NULL DEFAULT '0.00' COMMENT '按加权平均价计算的成本',
  `status` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'DRAFT',
  `remark` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `created_by` bigint DEFAULT NULL COMMENT '创建人(原始提交人)',
  `deleted` tinyint NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_doc_no` (`doc_no`,`deleted`),
  KEY `idx_req` (`source_req_id`),
  KEY `idx_project` (`project_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='材料出库头';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `biz_out_line`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `biz_out_line` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `out_id` bigint NOT NULL,
  `line_no` int NOT NULL DEFAULT '1',
  `source_req_line_id` bigint NOT NULL,
  `material_id` bigint NOT NULL,
  `qty` decimal(18,4) NOT NULL,
  `unit_price` decimal(18,4) NOT NULL DEFAULT '0.0000' COMMENT '出库时的加权平均单价',
  `amount` decimal(18,2) NOT NULL DEFAULT '0.00',
  `remark` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_out` (`out_id`),
  KEY `idx_material` (`material_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='材料出库明细';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `biz_out_req`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `biz_out_req` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `doc_no` varchar(40) COLLATE utf8mb4_unicode_ci NOT NULL,
  `template_id` bigint DEFAULT NULL COMMENT 'workflow template snapshot id',
  `nodes_json` text COLLATE utf8mb4_unicode_ci COMMENT 'workflow nodes snapshot',
  `applicant_id` bigint NOT NULL,
  `dept_id` bigint NOT NULL,
  `org_id` bigint NOT NULL,
  `warehouse_id` bigint NOT NULL,
  `project_id` bigint DEFAULT NULL COMMENT '工程项目ID',
  `project_name` varchar(120) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '工程项目名称快照',
  `construction_unit` varchar(120) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '施工单位',
  `out_type` varchar(40) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '出库类型',
  `business_category` varchar(40) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '业务分类',
  `material_applicant` varchar(40) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '材料申请人',
  `apply_date` date NOT NULL,
  `purpose` varchar(40) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '生产领用/办公领用/设备维修/其他',
  `approval_doc_no` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '特殊事项审批单据号（友空间表单编号，选填）',
  `total_qty` decimal(18,4) NOT NULL DEFAULT '0.0000',
  `out_qty` decimal(18,4) NOT NULL DEFAULT '0.0000' COMMENT '已出库数量',
  `status` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/PENDING/APPROVED/PARTIAL_OUT/OUT/CANCELLED',
  `remark` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` tinyint NOT NULL DEFAULT '0',
  `fulfillment_mode` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'LEGACY',
  `pickup_due_date` date DEFAULT NULL,
  `pickup_extensions` int NOT NULL DEFAULT '0',
  `pickup_notice_stage` int NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_doc_no` (`doc_no`,`deleted`),
  KEY `idx_status` (`status`),
  KEY `idx_applicant` (`applicant_id`),
  KEY `idx_project` (`project_id`),
  KEY `idx_pickup_due` (`fulfillment_mode`,`status`,`pickup_due_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='出库申请头';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `biz_out_req_line`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `biz_out_req_line` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `out_req_id` bigint NOT NULL,
  `line_no` int NOT NULL DEFAULT '1',
  `material_id` bigint NOT NULL,
  `qty` decimal(18,4) NOT NULL,
  `original_qty` decimal(18,4) NOT NULL DEFAULT '0.0000' COMMENT '原始计划数量',
  `revised_qty` decimal(18,4) NOT NULL DEFAULT '0.0000' COMMENT '累计修订数量',
  `out_qty` decimal(18,4) NOT NULL DEFAULT '0.0000',
  `reissue_qty` decimal(18,4) NOT NULL DEFAULT '0.0000' COMMENT '退回待再领数量(选了再领的已审退料累计)',
  `remark` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `transferred_qty` decimal(18,4) NOT NULL DEFAULT '0.0000',
  `closed_qty` decimal(18,4) NOT NULL DEFAULT '0.0000',
  PRIMARY KEY (`id`),
  KEY `idx_req` (`out_req_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='出库申请明细';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `biz_out_req_revision`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `biz_out_req_revision` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `doc_no` varchar(40) COLLATE utf8mb4_unicode_ci NOT NULL,
  `source_req_id` bigint NOT NULL,
  `source_req_no` varchar(40) COLLATE utf8mb4_unicode_ci NOT NULL,
  `org_id` bigint NOT NULL,
  `warehouse_id` bigint NOT NULL,
  `project_id` bigint DEFAULT NULL COMMENT '工程项目ID',
  `project_name` varchar(120) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '工程项目名称快照',
  `revision_date` date NOT NULL,
  `reason` varchar(500) COLLATE utf8mb4_unicode_ci NOT NULL,
  `total_delta_qty` decimal(18,4) NOT NULL DEFAULT '0.0000',
  `status` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'DRAFT',
  `remark` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` tinyint NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_doc_no` (`doc_no`,`deleted`),
  KEY `idx_source_req` (`source_req_id`),
  KEY `idx_status` (`status`),
  KEY `idx_revision_date` (`revision_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='出库申请修订单';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `biz_out_req_revision_line`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `biz_out_req_revision_line` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `revision_id` bigint NOT NULL,
  `req_line_id` bigint DEFAULT NULL COMMENT '为空表示新增材料计划行',
  `line_no` int NOT NULL DEFAULT '1',
  `material_id` bigint NOT NULL,
  `old_qty` decimal(18,4) NOT NULL DEFAULT '0.0000',
  `new_qty` decimal(18,4) NOT NULL,
  `delta_qty` decimal(18,4) NOT NULL DEFAULT '0.0000',
  `remark` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_revision` (`revision_id`),
  KEY `idx_req_line` (`req_line_id`),
  KEY `idx_material` (`material_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='出库申请修订明细';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `biz_payment`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `biz_payment` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `org_id` bigint NOT NULL COMMENT '付款公司(账套)',
  `supplier_id` bigint NOT NULL COMMENT '收款供应商',
  `pay_date` date NOT NULL COMMENT '付款日期',
  `amount` decimal(18,2) NOT NULL COMMENT '付款金额(含税口径，对应应付-物资款)',
  `method` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '付款方式：转账/银行承兑/商业承兑/现金/其他',
  `ref_no` varchar(60) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '银行回单号/记账凭证号',
  `remark` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `created_by` bigint DEFAULT NULL,
  `updated_by` bigint DEFAULT NULL,
  `deleted` tinyint NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_payment_org_sup` (`org_id`,`supplier_id`,`deleted`,`pay_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='付款登记(应付物资款核销)';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `biz_po`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `biz_po` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `doc_no` varchar(40) COLLATE utf8mb4_unicode_ci NOT NULL,
  `source_pr_id` bigint DEFAULT NULL COMMENT '来源采购申请ID（可空，紧急采购）',
  `source_pr_no` varchar(40) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `supplier_id` bigint NOT NULL,
  `warehouse_id` bigint NOT NULL COMMENT '默认入库仓库',
  `org_id` bigint NOT NULL,
  `order_date` date NOT NULL,
  `delivery_date` date DEFAULT NULL COMMENT '约定交期',
  `total_qty` decimal(18,4) NOT NULL DEFAULT '0.0000',
  `received_qty` decimal(18,4) NOT NULL DEFAULT '0.0000',
  `invoiced_qty` decimal(18,4) NOT NULL DEFAULT '0.0000',
  `total_amt` decimal(18,2) NOT NULL DEFAULT '0.00',
  `tax_amt` decimal(18,2) NOT NULL DEFAULT '0.00',
  `status` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/PENDING/APPROVED/PARTIAL_RECEIVED/RECEIVED/CLOSED/REJECTED',
  `remark` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `created_by` bigint DEFAULT NULL,
  `updated_by` bigint DEFAULT NULL,
  `deleted` tinyint NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_doc_no` (`doc_no`,`deleted`),
  KEY `idx_status` (`status`),
  KEY `idx_supplier` (`supplier_id`),
  KEY `idx_org` (`org_id`),
  KEY `idx_date` (`order_date`),
  KEY `idx_source_pr` (`source_pr_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='采购订单头';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `biz_po_line`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `biz_po_line` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `po_id` bigint NOT NULL,
  `line_no` int NOT NULL DEFAULT '1',
  `source_pr_line_id` bigint DEFAULT NULL,
  `material_id` bigint NOT NULL,
  `qty` decimal(18,4) NOT NULL,
  `received_qty` decimal(18,4) NOT NULL DEFAULT '0.0000',
  `invoiced_qty` decimal(18,4) NOT NULL DEFAULT '0.0000',
  `unit_price` decimal(18,4) NOT NULL,
  `tax_rate` decimal(5,2) NOT NULL DEFAULT '0.00',
  `amount` decimal(18,2) NOT NULL,
  `amount_excl_tax` decimal(18,2) DEFAULT NULL COMMENT '不含税金额',
  `tax_amount` decimal(18,2) NOT NULL DEFAULT '0.00',
  `remark` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `price_check_ref` decimal(18,4) DEFAULT NULL COMMENT '价格偏离时的历史参考含税单价',
  `price_check_note` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '价格偏离时制单人填写的确认理由',
  PRIMARY KEY (`id`),
  KEY `idx_po` (`po_id`),
  KEY `idx_material` (`material_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='采购订单明细';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `biz_pr`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `biz_pr` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `doc_no` varchar(40) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '单据号',
  `applicant_id` bigint NOT NULL COMMENT '申请人',
  `dept_id` bigint NOT NULL COMMENT '申请部门',
  `org_id` bigint NOT NULL,
  `apply_date` date NOT NULL,
  `required_date` date DEFAULT NULL COMMENT '需求日期',
  `purpose` varchar(40) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '用途',
  `supplier_id` bigint DEFAULT NULL COMMENT '建议供应商',
  `warehouse_id` bigint DEFAULT NULL COMMENT '下推采购订单默认入库仓库',
  `total_qty` decimal(18,4) NOT NULL DEFAULT '0.0000',
  `total_amt` decimal(18,2) NOT NULL DEFAULT '0.00',
  `status` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/PENDING/APPROVED/REJECTED/CANCELLED/CONVERTED',
  `current_node` varchar(40) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '当前审批节点',
  `remark` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `created_by` bigint DEFAULT NULL,
  `updated_by` bigint DEFAULT NULL,
  `deleted` tinyint NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_doc_no` (`doc_no`,`deleted`),
  KEY `idx_status` (`status`),
  KEY `idx_applicant` (`applicant_id`),
  KEY `idx_pr_warehouse` (`warehouse_id`),
  KEY `idx_org` (`org_id`),
  KEY `idx_date` (`apply_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='采购申请头';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `biz_pr_line`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `biz_pr_line` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `pr_id` bigint NOT NULL,
  `line_no` int NOT NULL DEFAULT '1',
  `material_id` bigint NOT NULL,
  `qty` decimal(18,4) NOT NULL,
  `unit_price` decimal(18,4) NOT NULL DEFAULT '0.0000',
  `amount` decimal(18,2) NOT NULL DEFAULT '0.00',
  `converted_qty` decimal(18,4) NOT NULL DEFAULT '0.0000' COMMENT '已转订单数量',
  `remark` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_pr` (`pr_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='采购申请明细';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `biz_purchase_return`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `biz_purchase_return` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `doc_no` varchar(40) COLLATE utf8mb4_unicode_ci NOT NULL,
  `source_recv_id` bigint NOT NULL COMMENT '来源采购入库单ID',
  `source_recv_no` varchar(40) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '来源采购入库单号',
  `source_po_id` bigint DEFAULT NULL COMMENT '来源采购订单ID(退货要回退其已入库数)',
  `source_po_no` varchar(40) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `recv_type` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '来源入库类型 ESTIMATE=暂估 INVOICE=发票入库',
  `supplier_id` bigint DEFAULT NULL,
  `warehouse_id` bigint NOT NULL,
  `org_id` bigint NOT NULL,
  `return_date` date NOT NULL COMMENT '业务日期:填实物实际拉走那天,不是录单那天',
  `returner` varchar(40) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '经办人',
  `reason` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '退货原因(质量问题/规格不符等)',
  `total_qty` decimal(18,4) NOT NULL DEFAULT '0.0000',
  `total_stock_amt` decimal(18,2) NOT NULL DEFAULT '0.00' COMMENT '库存冲减额(按移动加权均价,实际从库存拿走的钱)',
  `total_recv_amt` decimal(18,2) NOT NULL DEFAULT '0.00' COMMENT '原入库不含税额(红字发票/冲暂估的钱)',
  `total_diff_amt` decimal(18,2) NOT NULL DEFAULT '0.00' COMMENT '混批差额 = 库存冲减额 - 原入库额,进当期损益',
  `status` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/PENDING/APPROVED/REJECTED',
  `remark` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `created_by` bigint DEFAULT NULL COMMENT '创建人(谁提交谁删除)',
  `deleted` tinyint NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_doc_no` (`doc_no`,`deleted`),
  KEY `idx_source_recv` (`source_recv_id`),
  KEY `idx_source_po` (`source_po_id`),
  KEY `idx_org` (`org_id`),
  KEY `idx_status` (`status`),
  KEY `idx_return_date` (`return_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='采购退货单头';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `biz_purchase_return_line`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `biz_purchase_return_line` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `return_id` bigint NOT NULL,
  `line_no` int NOT NULL DEFAULT '1',
  `source_recv_line_id` bigint NOT NULL COMMENT '来源入库行ID(退货按行,不动同单其他行)',
  `source_po_line_id` bigint DEFAULT NULL COMMENT '来源采购订单行ID(回退已入库数用)',
  `material_id` bigint NOT NULL,
  `qty` decimal(18,4) NOT NULL COMMENT '退货数量',
  `recv_unit_price` decimal(18,4) NOT NULL COMMENT '原入库单价(不含税)',
  `recv_amt` decimal(18,2) NOT NULL COMMENT '原入库不含税额(按退货数量等比折算)',
  `stock_unit_price` decimal(18,4) DEFAULT NULL COMMENT '实际出库单价(过账时的加权均价,审批时回填)',
  `stock_amt` decimal(18,2) DEFAULT NULL COMMENT '实际库存冲减额(审批时回填)',
  `diff_amt` decimal(18,2) DEFAULT NULL COMMENT '本行混批差额 = stock_amt - recv_amt',
  `remark` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_return` (`return_id`),
  KEY `idx_source_recv_line` (`source_recv_line_id`),
  KEY `idx_material` (`material_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='采购退货单明细';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `biz_receipt`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `biz_receipt` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `org_id` bigint NOT NULL COMMENT '收款公司(账套)',
  `customer_id` bigint DEFAULT NULL COMMENT '付款客户(往来单位，散户可空)',
  `customer_name` varchar(160) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '客户名称(散户个人姓名)',
  `receipt_date` date NOT NULL COMMENT '收款日期',
  `amount` decimal(18,2) NOT NULL COMMENT '收款金额(含税口径，对应应收)',
  `method` varchar(20) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '收款方式：转账/银行承兑/现金/微信/支付宝/其他',
  `ref_no` varchar(60) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '银行回单号/凭证号',
  `remark` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `created_by` bigint DEFAULT NULL,
  `updated_by` bigint DEFAULT NULL,
  `deleted` tinyint NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_receipt_org_cus` (`org_id`,`customer_id`,`deleted`,`receipt_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='收款登记(销售应收核销)';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `biz_recv`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `biz_recv` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `doc_no` varchar(40) COLLATE utf8mb4_unicode_ci NOT NULL,
  `source_po_id` bigint NOT NULL,
  `source_po_no` varchar(40) COLLATE utf8mb4_unicode_ci NOT NULL,
  `supplier_id` bigint NOT NULL,
  `warehouse_id` bigint NOT NULL,
  `org_id` bigint NOT NULL,
  `recv_date` date NOT NULL,
  `recv_type` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'ESTIMATE暂估/INVOICE发票入库',
  `invoice_id` bigint DEFAULT NULL COMMENT '关联发票（暂估转正后填）',
  `invoice_no` varchar(40) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `delivery_no` varchar(40) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '送货单号',
  `carrier` varchar(80) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '承运商',
  `total_qty` decimal(18,4) NOT NULL DEFAULT '0.0000',
  `total_amt` decimal(18,2) NOT NULL DEFAULT '0.00',
  `status` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/PENDING/APPROVED/CANCELLED',
  `remark` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `created_by` bigint DEFAULT NULL,
  `updated_by` bigint DEFAULT NULL,
  `deleted` tinyint NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_doc_no` (`doc_no`,`deleted`),
  KEY `idx_po` (`source_po_id`),
  KEY `idx_invoice` (`invoice_id`),
  KEY `idx_status` (`status`),
  KEY `idx_date` (`recv_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='采购入库头';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `biz_recv_line`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `biz_recv_line` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `recv_id` bigint NOT NULL,
  `line_no` int NOT NULL DEFAULT '1',
  `source_po_line_id` bigint NOT NULL,
  `material_id` bigint NOT NULL,
  `qty` decimal(18,4) NOT NULL,
  `unit_price` decimal(18,4) NOT NULL,
  `amount` decimal(18,2) NOT NULL,
  `amount_incl_tax` decimal(18,2) DEFAULT NULL COMMENT '含税金额(按订单行含税额分摊,末笔收尾;税额=含税-不含税)',
  `invoiced_qty` decimal(18,4) NOT NULL DEFAULT '0.0000' COMMENT '已开票数量',
  `returned_qty` decimal(18,4) NOT NULL DEFAULT '0.0000' COMMENT '已退供应商数量(退货后这部分不再等发票)',
  `remark` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_recv` (`recv_id`),
  KEY `idx_material` (`material_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='采购入库明细';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `biz_req_fulfillment_event`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `biz_req_fulfillment_event` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `req_id` bigint NOT NULL,
  `event_type` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL,
  `status` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL,
  `reason` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `review_reason` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `lines_json` text COLLATE utf8mb4_unicode_ci,
  `due_date` date DEFAULT NULL,
  `created_by` bigint DEFAULT NULL,
  `reviewed_by` bigint DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `reviewed_at` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_req_event` (`req_id`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `biz_return`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `biz_return` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `doc_no` varchar(40) COLLATE utf8mb4_unicode_ci NOT NULL,
  `source_out_id` bigint DEFAULT NULL,
  `source_out_no` varchar(40) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `source_req_id` bigint DEFAULT NULL COMMENT '来源出库申请ID',
  `source_req_no` varchar(40) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `warehouse_id` bigint NOT NULL,
  `dept_id` bigint NOT NULL,
  `org_id` bigint NOT NULL,
  `project_id` bigint DEFAULT NULL COMMENT '工程项目ID',
  `project_name` varchar(120) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '工程项目名称快照',
  `construction_unit` varchar(120) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '施工单位',
  `out_type` varchar(40) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '原出库类型',
  `business_category` varchar(40) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '业务分类',
  `returner` varchar(40) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '退料人',
  `return_date` date NOT NULL,
  `total_qty` decimal(18,4) NOT NULL DEFAULT '0.0000',
  `total_amt` decimal(18,2) NOT NULL DEFAULT '0.00' COMMENT '按原出库单价回冲的参考金额',
  `status` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'DRAFT',
  `reissue` tinyint NOT NULL DEFAULT '0' COMMENT '退回后还要再领:1=审批时回补原申请可出数量;0=不再领(默认)',
  `remark` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `created_by` bigint DEFAULT NULL COMMENT '创建人(原始提交人)',
  `deleted` tinyint NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_doc_no` (`doc_no`,`deleted`),
  KEY `idx_source_out` (`source_out_id`),
  KEY `idx_source_req` (`source_req_id`),
  KEY `idx_project` (`project_id`),
  KEY `idx_status` (`status`),
  KEY `idx_return_date` (`return_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='退料单头';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `biz_return_line`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `biz_return_line` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `return_id` bigint NOT NULL,
  `line_no` int NOT NULL DEFAULT '1',
  `source_out_line_id` bigint NOT NULL,
  `source_req_line_id` bigint DEFAULT NULL,
  `material_id` bigint NOT NULL,
  `qty` decimal(18,4) NOT NULL,
  `unit_price` decimal(18,4) NOT NULL DEFAULT '0.0000',
  `amount` decimal(18,2) NOT NULL DEFAULT '0.00',
  `remark` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_return` (`return_id`),
  KEY `idx_source_out_line` (`source_out_line_id`),
  KEY `idx_source_req_line` (`source_req_line_id`),
  KEY `idx_material` (`material_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='退料单明细';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `biz_sale_invoice`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `biz_sale_invoice` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `doc_no` varchar(60) COLLATE utf8mb4_unicode_ci NOT NULL,
  `org_id` bigint NOT NULL,
  `source_out_id` bigint DEFAULT NULL COMMENT '来源销售出库',
  `source_out_no` varchar(60) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `customer_id` bigint DEFAULT NULL,
  `customer_unit` varchar(160) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `customer_name` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `invoice_type` varchar(20) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '增值税专用发票/普通发票',
  `invoice_no` varchar(60) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '发票号',
  `invoice_date` date NOT NULL,
  `total_qty` decimal(18,4) NOT NULL DEFAULT '0.0000',
  `amount` decimal(18,2) NOT NULL DEFAULT '0.00' COMMENT '不含税金额',
  `tax_amt` decimal(18,2) NOT NULL DEFAULT '0.00' COMMENT '销项税额',
  `total_amt` decimal(18,2) NOT NULL DEFAULT '0.00' COMMENT '价税合计(应收)',
  `status` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/PENDING/APPROVED/REJECTED',
  `remark` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `created_by` bigint DEFAULT NULL,
  `updated_by` bigint DEFAULT NULL,
  `deleted` tinyint NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_saleinv_org` (`org_id`,`deleted`,`invoice_date`),
  KEY `idx_saleinv_out` (`source_out_id`),
  KEY `idx_saleinv_customer` (`customer_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='销售发票';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `biz_sale_invoice_line`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `biz_sale_invoice_line` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `invoice_id` bigint NOT NULL,
  `line_no` int NOT NULL DEFAULT '0',
  `material_id` bigint NOT NULL,
  `qty` decimal(18,4) NOT NULL DEFAULT '0.0000',
  `tax_rate` decimal(6,2) NOT NULL DEFAULT '0.00',
  `amount_excl_tax` decimal(18,2) NOT NULL DEFAULT '0.00',
  `tax_amount` decimal(18,2) NOT NULL DEFAULT '0.00',
  `amount` decimal(18,2) NOT NULL DEFAULT '0.00' COMMENT '价税合计',
  `remark` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_saleinvline_inv` (`invoice_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='销售发票明细';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `biz_sale_out`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `biz_sale_out` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `doc_no` varchar(60) COLLATE utf8mb4_unicode_ci NOT NULL,
  `org_id` bigint NOT NULL,
  `source_so_id` bigint DEFAULT NULL COMMENT '来源销售订单',
  `source_so_no` varchar(60) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `warehouse_id` bigint NOT NULL,
  `customer_id` bigint DEFAULT NULL,
  `customer_unit` varchar(160) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `customer_name` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `customer_phone` varchar(40) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `ship_address` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `dept_id` bigint DEFAULT NULL,
  `dept_name` varchar(80) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `sales_person` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `biz_flow` varchar(40) COLLATE utf8mb4_unicode_ci DEFAULT '普通销售' COMMENT '业务流程',
  `out_type` varchar(40) COLLATE utf8mb4_unicode_ci DEFAULT '普通销售出库' COMMENT '出入库类型',
  `out_date` date NOT NULL,
  `total_qty` decimal(18,4) NOT NULL DEFAULT '0.0000',
  `total_amt` decimal(18,2) NOT NULL DEFAULT '0.00' COMMENT '销售成本额(移动加权)',
  `status` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/PENDING/APPROVED/REJECTED',
  `remark` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `created_by` bigint DEFAULT NULL,
  `updated_by` bigint DEFAULT NULL,
  `deleted` tinyint NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_saleout_org` (`org_id`,`deleted`,`out_date`),
  KEY `idx_saleout_so` (`source_so_id`),
  KEY `idx_saleout_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='销售出库单';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `biz_sale_out_line`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `biz_sale_out_line` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `out_id` bigint NOT NULL,
  `line_no` int NOT NULL DEFAULT '0',
  `source_so_line_id` bigint DEFAULT NULL,
  `material_id` bigint NOT NULL,
  `qty` decimal(18,4) NOT NULL COMMENT '实发数量',
  `unit_price` decimal(18,4) NOT NULL DEFAULT '0.0000' COMMENT '成本单价(移动加权，审批时填入)',
  `amount` decimal(18,2) NOT NULL DEFAULT '0.00' COMMENT '成本额',
  `remark` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_saleoutline_out` (`out_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='销售出库明细';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `biz_sale_return`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `biz_sale_return` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `doc_no` varchar(60) COLLATE utf8mb4_unicode_ci NOT NULL,
  `org_id` bigint NOT NULL,
  `source_out_id` bigint DEFAULT NULL COMMENT '来源销售出库单',
  `source_out_no` varchar(60) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `warehouse_id` bigint NOT NULL,
  `customer_id` bigint DEFAULT NULL,
  `customer_unit` varchar(160) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `customer_name` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `return_date` date NOT NULL,
  `total_qty` decimal(18,4) NOT NULL DEFAULT '0.0000',
  `total_amt` decimal(18,2) NOT NULL DEFAULT '0.00' COMMENT '退货成本额(回冲COGS)',
  `refund_amt` decimal(18,2) NOT NULL DEFAULT '0.00' COMMENT '应冲收入(价税合计，冲减应收)',
  `status` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/PENDING/APPROVED/REJECTED',
  `remark` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `created_by` bigint DEFAULT NULL,
  `updated_by` bigint DEFAULT NULL,
  `deleted` tinyint NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_saleret_org` (`org_id`,`deleted`,`return_date`),
  KEY `idx_saleret_out` (`source_out_id`),
  KEY `idx_saleret_customer` (`customer_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='销售退货单';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `biz_sale_return_line`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `biz_sale_return_line` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `return_id` bigint NOT NULL,
  `line_no` int NOT NULL DEFAULT '0',
  `source_out_line_id` bigint DEFAULT NULL,
  `material_id` bigint NOT NULL,
  `qty` decimal(18,4) NOT NULL COMMENT '退货数量',
  `unit_price` decimal(18,4) NOT NULL DEFAULT '0.0000' COMMENT '原出库成本单价',
  `amount` decimal(18,2) NOT NULL DEFAULT '0.00' COMMENT '退货成本额',
  `sale_tax_price` decimal(18,4) NOT NULL DEFAULT '0.0000' COMMENT '原售价含税单价',
  `sale_amount` decimal(18,2) NOT NULL DEFAULT '0.00' COMMENT '应冲收入(价税合计)',
  `remark` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_saleretline_ret` (`return_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='销售退货明细';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `biz_so`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `biz_so` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `doc_no` varchar(60) COLLATE utf8mb4_unicode_ci NOT NULL,
  `org_id` bigint NOT NULL COMMENT '销售组织(账套)',
  `order_type` varchar(40) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '订单类型：普通/…',
  `channel` varchar(60) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '销售渠道',
  `customer_id` bigint DEFAULT NULL COMMENT '客户单位(往来单位 md_supplier.id，散户可空)',
  `customer_unit` varchar(160) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '客户单位名称(冗余，散户可空)',
  `customer_name` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '客户姓名(个人)',
  `customer_phone` varchar(40) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '联系方式',
  `ship_address` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '收货地址',
  `dept_id` bigint DEFAULT NULL,
  `dept_name` varchar(80) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '部门',
  `sales_person` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '业务员',
  `order_date` date NOT NULL COMMENT '单据日期',
  `plan_ship_date` date DEFAULT NULL COMMENT '计划发货日期',
  `total_qty` decimal(18,4) NOT NULL DEFAULT '0.0000',
  `total_amt` decimal(18,2) NOT NULL DEFAULT '0.00' COMMENT '价税合计',
  `tax_amt` decimal(18,2) NOT NULL DEFAULT '0.00' COMMENT '税额合计',
  `out_qty` decimal(18,4) NOT NULL DEFAULT '0.0000' COMMENT '已出库数量',
  `invoice_qty` decimal(18,4) NOT NULL DEFAULT '0.0000' COMMENT '已开票数量',
  `receipt_amount` decimal(18,2) NOT NULL DEFAULT '0.00' COMMENT '收款金额(订单预收)',
  `receipt_method` varchar(20) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '收款方式',
  `status` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/PENDING/APPROVED/PARTIAL_OUT/OUT/REJECTED/CANCELLED',
  `remark` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `created_by` bigint DEFAULT NULL,
  `updated_by` bigint DEFAULT NULL,
  `deleted` tinyint NOT NULL DEFAULT '0',
  `source_link_id` bigint DEFAULT NULL COMMENT '来源:网点扫码录单的链接ID(NULL=后台直接建的)',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_so_doc` (`doc_no`,`deleted`),
  KEY `idx_so_org` (`org_id`,`deleted`,`order_date`),
  KEY `idx_so_customer` (`customer_id`),
  KEY `idx_so_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='销售订单';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `biz_so_line`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `biz_so_line` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `so_id` bigint NOT NULL,
  `line_no` int NOT NULL DEFAULT '0',
  `material_id` bigint NOT NULL,
  `qty` decimal(18,4) NOT NULL COMMENT '主数量',
  `tax_price` decimal(18,4) NOT NULL DEFAULT '0.0000' COMMENT '含税净价',
  `tax_rate` decimal(6,2) NOT NULL DEFAULT '0.00',
  `amount` decimal(18,2) NOT NULL DEFAULT '0.00' COMMENT '价税合计',
  `amount_excl_tax` decimal(18,2) NOT NULL DEFAULT '0.00' COMMENT '不含税金额',
  `tax_amount` decimal(18,2) NOT NULL DEFAULT '0.00' COMMENT '税额',
  `unit_price` decimal(18,4) NOT NULL DEFAULT '0.0000' COMMENT '不含税单价',
  `plan_ship_date` date DEFAULT NULL,
  `out_qty` decimal(18,4) NOT NULL DEFAULT '0.0000' COMMENT '已出库',
  `invoice_qty` decimal(18,4) NOT NULL DEFAULT '0.0000' COMMENT '已开票',
  `remark` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_sol_so` (`so_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='销售订单明细';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `biz_stocktake`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `biz_stocktake` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `doc_no` varchar(40) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `org_id` bigint NOT NULL COMMENT '账套/公司',
  `warehouse_id` bigint NOT NULL,
  `take_date` date NOT NULL COMMENT '盘点日期',
  `status` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/SUBMITTED/APPROVED/REJECTED',
  `line_cnt` int NOT NULL DEFAULT '0' COMMENT '盘点行数',
  `gain_amt` decimal(18,2) NOT NULL DEFAULT '0.00' COMMENT '盘盈金额(确认时写)',
  `loss_amt` decimal(18,2) NOT NULL DEFAULT '0.00' COMMENT '盘亏金额(确认时写,正数)',
  `finance_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '财务确认人(记名)',
  `finance_at` datetime DEFAULT NULL,
  `reject_reason` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `remark` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `created_by` bigint DEFAULT NULL,
  `updated_by` bigint DEFAULT NULL,
  `deleted` tinyint NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_stk_doc` (`doc_no`,`deleted`),
  KEY `idx_stk_org` (`org_id`,`deleted`,`status`),
  KEY `idx_stk_wh` (`warehouse_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='盘点单';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `biz_stocktake_line`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `biz_stocktake_line` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `stocktake_id` bigint NOT NULL,
  `line_no` int NOT NULL,
  `material_id` bigint NOT NULL,
  `book_qty` decimal(18,4) NOT NULL DEFAULT '0.0000' COMMENT '账面数量(建单快照)',
  `unit_price` decimal(18,6) NOT NULL DEFAULT '0.000000' COMMENT '结存单价(建单快照,加权平均)',
  `actual_qty` decimal(18,4) DEFAULT NULL COMMENT '实盘数量(空=未盘,不调整)',
  `diff_qty` decimal(18,4) DEFAULT NULL COMMENT '差异=实盘-账面(确认时写)',
  `diff_amt` decimal(18,2) DEFAULT NULL COMMENT '差异金额(确认时写)',
  `remark` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_stkl_head` (`stocktake_id`),
  KEY `idx_stkl_mat` (`material_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='盘点单明细';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `biz_transfer`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `biz_transfer` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `doc_no` varchar(40) COLLATE utf8mb4_unicode_ci NOT NULL,
  `from_warehouse_id` bigint NOT NULL,
  `to_warehouse_id` bigint NOT NULL,
  `org_id` bigint NOT NULL,
  `transfer_date` date NOT NULL,
  `transferer` varchar(40) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `total_qty` decimal(18,4) NOT NULL DEFAULT '0.0000',
  `total_amt` decimal(18,2) NOT NULL DEFAULT '0.00',
  `status` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'DRAFT',
  `remark` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `created_by` bigint DEFAULT NULL,
  `updated_by` bigint DEFAULT NULL,
  `deleted` tinyint NOT NULL DEFAULT '0',
  `source_req_id` bigint DEFAULT NULL,
  `source_req_no` varchar(40) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `handover_by` bigint DEFAULT NULL,
  `handover_at` datetime DEFAULT NULL,
  `handover_receiver` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_doc_no` (`doc_no`,`deleted`),
  KEY `idx_from_warehouse` (`from_warehouse_id`),
  KEY `idx_to_warehouse` (`to_warehouse_id`),
  KEY `idx_org` (`org_id`),
  KEY `idx_status` (`status`),
  KEY `idx_transfer_date` (`transfer_date`),
  KEY `idx_transfer_req` (`source_req_id`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='inventory transfer header';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `biz_transfer_line`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `biz_transfer_line` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `transfer_id` bigint NOT NULL,
  `line_no` int NOT NULL DEFAULT '1',
  `material_id` bigint NOT NULL,
  `qty` decimal(18,4) NOT NULL,
  `unit_price` decimal(18,4) NOT NULL DEFAULT '0.0000',
  `amount` decimal(18,2) NOT NULL DEFAULT '0.00',
  `remark` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `source_req_line_id` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_transfer` (`transfer_id`),
  KEY `idx_material` (`material_id`),
  KEY `idx_transfer_req_line` (`source_req_line_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='inventory transfer detail';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `doc_relation`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `doc_relation` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `source_type` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'PR/PO/RECV/INV/OREQ/OREQ_REV/OUT/RETURN/LOAN',
  `source_id` bigint NOT NULL,
  `source_no` varchar(40) COLLATE utf8mb4_unicode_ci NOT NULL,
  `target_type` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL,
  `target_id` bigint NOT NULL,
  `target_no` varchar(40) COLLATE utf8mb4_unicode_ci NOT NULL,
  `relation_type` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'DOWN_PUSH' COMMENT 'DOWN_PUSH下推/MATCH核销/OFFSET冲销/CANCEL级联取消',
  `source_line_id` bigint DEFAULT NULL,
  `target_line_id` bigint DEFAULT NULL,
  `qty` decimal(18,4) DEFAULT '0.0000',
  `amount` decimal(18,2) DEFAULT '0.00',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `created_by` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_src` (`source_type`,`source_id`),
  KEY `idx_tgt` (`target_type`,`target_id`),
  KEY `idx_src_no` (`source_no`),
  KEY `idx_tgt_no` (`target_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='单据关系（双向溯源）';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `fin_estimate_diff_confirm`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `fin_estimate_diff_confirm` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `org_id` bigint NOT NULL COMMENT '账套(公司)',
  `period_month` varchar(7) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '所属期间 YYYY-MM(按开票日期归属)',
  `diff_amount` decimal(18,2) NOT NULL DEFAULT '0.00' COMMENT '确认当时的本期差异合计(留痕:事后新增差异可对比出来)',
  `entry_count` int NOT NULL DEFAULT '0' COMMENT '确认当时覆盖的差异笔数',
  `confirmed_by` bigint DEFAULT NULL COMMENT '确认人(材料会计)',
  `confirmed_name` varchar(60) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '确认人姓名(冗余,防人员变动后查不到)',
  `confirmed_at` datetime DEFAULT NULL COMMENT '确认时间',
  `remark` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '备注:外部账凭证号等',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` tinyint NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_org_period` (`org_id`,`period_month`,`deleted`),
  KEY `idx_period` (`period_month`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='暂估差异月度入账确认';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `fin_period_close`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `fin_period_close` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `org_id` bigint NOT NULL,
  `period_month` varchar(7) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '库存期间，YYYY-MM',
  `status` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'CLOSED' COMMENT 'CLOSED/REOPENED',
  `issue_count` int NOT NULL DEFAULT '0' COMMENT '锁定检查异常数',
  `warning_count` int NOT NULL DEFAULT '0' COMMENT '锁定检查提醒数',
  `check_result` longtext COLLATE utf8mb4_unicode_ci COMMENT '锁定检查结果快照',
  `remark` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `checked_at` datetime DEFAULT NULL,
  `closed_at` datetime DEFAULT NULL,
  `closed_by` bigint DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` tinyint NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_period_org` (`org_id`,`period_month`,`deleted`),
  KEY `idx_period_status` (`period_month`,`status`,`deleted`),
  KEY `idx_org_status` (`org_id`,`status`,`deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='库存期间锁定记录';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `inv_balance`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `inv_balance` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `material_id` bigint NOT NULL,
  `warehouse_id` bigint NOT NULL,
  `qty` decimal(18,4) NOT NULL DEFAULT '0.0000' COMMENT '结存数量',
  `amount` decimal(18,2) NOT NULL DEFAULT '0.00' COMMENT '结存金额',
  `avg_price` decimal(18,4) NOT NULL DEFAULT '0.0000' COMMENT '加权平均结存单价',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `version` int NOT NULL DEFAULT '0' COMMENT '乐观锁',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_mat_wh` (`material_id`,`warehouse_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='库存余额（实时）';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `inv_cost_baseline`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `inv_cost_baseline` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `rollback_id` bigint NOT NULL COMMENT 'inv_cost_cutover 里那次 ROLLBACK 的 id',
  `warehouse_id` bigint NOT NULL,
  `material_id` bigint NOT NULL,
  `qty_drift` decimal(18,4) NOT NULL COMMENT '回滚时 余额数量 − 流水数量累计',
  `amount_drift` decimal(18,2) NOT NULL COMMENT '回滚时 余额金额 − 流水金额累计(主要是按公司期间的分摊)',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_baseline` (`rollback_id`,`warehouse_id`,`material_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='存货核算回滚时各仓与流水的差额基点';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `inv_cost_cutover`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `inv_cost_cutover` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `action` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'CUTOVER 切换 / ROLLBACK 回滚',
  `scope_after` varchar(10) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'ORG 按公司 / WH 按仓库',
  `operator` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `remark` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `flow_id_at` bigint NOT NULL DEFAULT '0' COMMENT '切换/回滚那一刻 inv_transaction 最大 id(判断之后有没有新流水;不用时间,避免数据库与应用时区不一致误判)',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='存货核算口径切换记录';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `inv_cost_cutover_snapshot`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `inv_cost_cutover_snapshot` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `cutover_id` bigint NOT NULL,
  `org_id` bigint NOT NULL,
  `warehouse_id` bigint NOT NULL,
  `material_id` bigint NOT NULL,
  `qty` decimal(18,4) NOT NULL,
  `old_amount` decimal(18,2) NOT NULL,
  `old_avg_price` decimal(18,4) NOT NULL,
  `pool_qty` decimal(18,4) NOT NULL,
  `pool_amount` decimal(18,2) NOT NULL,
  `pool_price` decimal(18,4) NOT NULL,
  `new_amount` decimal(18,2) NOT NULL COMMENT '切换后本仓参考金额(按数量占比分摊)',
  `shift_amount` decimal(18,2) NOT NULL COMMENT 'new_amount - old_amount',
  `suspect` tinyint NOT NULL DEFAULT '0' COMMENT '1=本仓单价低于同物料最高仓单价 20%,疑似错价(只提示)',
  PRIMARY KEY (`id`),
  KEY `idx_cutover` (`cutover_id`,`org_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='存货核算切换前后各仓对照(凭据)';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `inv_cost_lock`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `inv_cost_lock` (
  `org_id` bigint NOT NULL,
  PRIMARY KEY (`org_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='公司成本锁(只用于 FOR UPDATE)';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `inv_cost_pool`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `inv_cost_pool` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `org_id` bigint NOT NULL COMMENT '公司(= md_warehouse.org_id)',
  `material_id` bigint NOT NULL,
  `qty` decimal(18,4) NOT NULL DEFAULT '0.0000' COMMENT '公司结存数量 = 各仓数量之和',
  `amount` decimal(18,2) NOT NULL DEFAULT '0.00' COMMENT '公司结存金额(唯一权威金额)',
  `avg_price` decimal(18,4) NOT NULL DEFAULT '0.0000' COMMENT '公司结存单价(按 scm.inv.avg-scale 取两位)',
  `version` int NOT NULL DEFAULT '0',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_org_mat` (`org_id`,`material_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='公司成本池(按公司核算)';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `inv_transaction`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `inv_transaction` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `trans_no` varchar(40) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '流水号',
  `material_id` bigint NOT NULL,
  `warehouse_id` bigint NOT NULL,
  `trans_type` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'INIT期初/IN_ESTIMATE暂估入/IN_INVOICE发票入/OUT材料出/RETURN退料入/LOAN借料出/LOAN_RETURN借料归还/ADJUST差额调整/REVERSE反审',
  `biz_doc_type` varchar(20) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '业务单据类型',
  `biz_doc_id` bigint DEFAULT NULL COMMENT '业务单据ID',
  `biz_doc_no` varchar(40) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '业务单据号',
  `biz_line_id` bigint DEFAULT NULL COMMENT '业务单据行ID',
  `qty` decimal(18,4) NOT NULL DEFAULT '0.0000' COMMENT '正数=入库，负数=出库',
  `unit_price` decimal(18,4) NOT NULL DEFAULT '0.0000',
  `amount` decimal(18,2) NOT NULL DEFAULT '0.00',
  `bal_qty` decimal(18,4) NOT NULL COMMENT '本次完成后结存数量',
  `bal_amount` decimal(18,2) NOT NULL COMMENT '本次完成后结存金额',
  `bal_avg_price` decimal(18,4) NOT NULL COMMENT '本次完成后结存单价',
  `remark` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `created_by` bigint DEFAULT NULL,
  `reversal_of_id` bigint DEFAULT NULL COMMENT '被冲销的原流水ID',
  `cost_scope` varchar(10) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'WH 按仓 / ORG 按公司;NULL=切换前旧流水(按仓)',
  `org_bal_qty` decimal(18,4) DEFAULT NULL COMMENT '按公司核算:本次完成后公司结存数量',
  `org_bal_amount` decimal(18,2) DEFAULT NULL COMMENT '按公司核算:本次完成后公司结存金额',
  `tail_amt` decimal(18,2) DEFAULT NULL COMMENT '其中尾差 = 金额 - 数量×单价(出光取余额/进位封顶时非空)',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_inv_reversal_of` (`reversal_of_id`),
  KEY `idx_mat_wh` (`material_id`,`warehouse_id`),
  KEY `idx_biz` (`biz_doc_type`,`biz_doc_no`),
  KEY `idx_time` (`created_at`),
  KEY `idx_type` (`trans_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='出入库流水（核心，可回放重算）';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `lr_link`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `lr_link` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `org_id` bigint NOT NULL COMMENT '所属公司(账套)',
  `link_type` varchar(10) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'LR' COMMENT 'LR=领料申请提交 SO=网点销售录单',
  `token` varchar(48) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '免登录提交令牌(URL携带)',
  `label` varchar(80) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '链接备注名',
  `warehouse_id` bigint NOT NULL COMMENT '默认仓库(申请显示该仓库存、生成出库申请用)',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '1启用 0停用',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `created_by` bigint DEFAULT NULL,
  `updated_by` bigint DEFAULT NULL,
  `deleted` tinyint NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_lr_link_token` (`token`),
  KEY `idx_lr_link_org` (`org_id`,`deleted`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='公司级领料申请提交链接';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `lr_picker`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `lr_picker` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `org_id` bigint NOT NULL COMMENT '所属公司(账套)',
  `name` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '领料人姓名',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '1启用 0停用',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `created_by` bigint DEFAULT NULL,
  `updated_by` bigint DEFAULT NULL,
  `deleted` tinyint NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_lr_picker_org` (`org_id`,`deleted`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='领料人档案';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `lr_request`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `lr_request` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `doc_no` varchar(40) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '领料申请单号',
  `org_id` bigint NOT NULL COMMENT '公司(账套)',
  `link_id` bigint DEFAULT NULL COMMENT '来源提交链接',
  `warehouse_id` bigint NOT NULL COMMENT '仓库(取自链接默认仓)',
  `applicant_name` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '填表/经办人(免登录手填，仅留痕)',
  `picker_id` bigint DEFAULT NULL COMMENT '领料人id',
  `picker_name` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '领料人姓名(快照)',
  `project_name` varchar(160) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '工程名称',
  `site` varchar(160) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '施工地点',
  `biz_type` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '业务类型',
  `apply_date` date DEFAULT NULL COMMENT '申请日期',
  `status` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'SUBMITTED',
  `purchase_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `purchase_by_id` bigint DEFAULT NULL COMMENT '采购受理人userId(生成出库申请作发起人)',
  `purchase_at` datetime DEFAULT NULL,
  `warehouse_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `warehouse_at` datetime DEFAULT NULL,
  `finance_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `finance_at` datetime DEFAULT NULL,
  `reject_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `reject_at` datetime DEFAULT NULL,
  `reject_reason` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `out_req_id` bigint DEFAULT NULL COMMENT '生成的出库申请id',
  `out_req_no` varchar(40) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '生成的出库申请单号',
  `remark` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` tinyint NOT NULL DEFAULT '0',
  `project_id` bigint DEFAULT NULL COMMENT '工程项目id（生成出库申请用）',
  `out_type` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '出库类型',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_lr_request_no` (`doc_no`),
  KEY `idx_lr_request_org` (`org_id`,`deleted`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='领料申请单(计划，不动库存)';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `lr_request_line`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `lr_request_line` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `request_id` bigint NOT NULL,
  `line_no` int NOT NULL DEFAULT '1',
  `material_id` bigint NOT NULL,
  `qty` decimal(18,3) NOT NULL DEFAULT '0.000' COMMENT '申领数量',
  `remark` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_lr_req_line_req` (`request_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='领料申请明细';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `md_material`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `md_material` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `code` varchar(40) COLLATE utf8mb4_unicode_ci NOT NULL,
  `name` varchar(120) COLLATE utf8mb4_unicode_ci NOT NULL,
  `spec` varchar(120) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '规格型号',
  `unit` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '个',
  `cat_id` bigint DEFAULT NULL COMMENT '物料分类',
  `material_type` varchar(40) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '物料类型',
  `org_id` bigint NOT NULL DEFAULT '1' COMMENT '所属组织',
  `is_shared` tinyint NOT NULL DEFAULT '1' COMMENT '是否集团共享',
  `default_tax` decimal(5,2) DEFAULT '13.00',
  `is_batch` tinyint NOT NULL DEFAULT '0' COMMENT '是否批次管理',
  `safety_qty` decimal(18,4) DEFAULT '0.0000' COMMENT '安全库存',
  `max_qty` decimal(18,4) DEFAULT NULL COMMENT '最高库存（超过则超储预警，NULL/0=不设）',
  `status` tinyint NOT NULL DEFAULT '1',
  `remark` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` bigint NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_code` (`code`,`deleted`),
  KEY `idx_cat` (`cat_id`),
  KEY `idx_org` (`org_id`),
  KEY `idx_shared` (`is_shared`),
  KEY `idx_md_material_org` (`org_id`),
  KEY `idx_md_material_shared` (`is_shared`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='物料档案';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `md_material_cat`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `md_material_cat` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `parent_id` bigint NOT NULL DEFAULT '0',
  `code` varchar(40) COLLATE utf8mb4_unicode_ci NOT NULL,
  `name` varchar(80) COLLATE utf8mb4_unicode_ci NOT NULL,
  `sort` int NOT NULL DEFAULT '0',
  `status` tinyint NOT NULL DEFAULT '1',
  `deleted` tinyint NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_code` (`code`,`deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='物料分类';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `md_material_org_scope`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `md_material_org_scope` (
  `material_id` bigint NOT NULL,
  `org_id` bigint NOT NULL,
  PRIMARY KEY (`material_id`,`org_id`),
  KEY `idx_org` (`org_id`,`material_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='物料组织授权范围';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `md_out_type_subject`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `md_out_type_subject` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `category` varchar(40) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '适用范围:通用/企业自定义',
  `out_type` varchar(60) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '出库类型',
  `subject` varchar(120) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '对应总账借方科目(全路径,反斜杠分层)',
  `sort` int NOT NULL DEFAULT '0',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '1启用 0停用',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `created_by` bigint DEFAULT NULL,
  `updated_by` bigint DEFAULT NULL,
  `deleted` tinyint NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ots_type` (`out_type`,`deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='出库类型-会计科目映射';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `md_project`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `md_project` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `parent_id` bigint NOT NULL DEFAULT '0',
  `org_id` bigint NOT NULL,
  `code` varchar(40) COLLATE utf8mb4_unicode_ci NOT NULL,
  `name` varchar(120) COLLATE utf8mb4_unicode_ci NOT NULL,
  `short_name` varchar(80) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `mnemonic_code` varchar(80) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `category` varchar(40) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `status` tinyint NOT NULL DEFAULT '1',
  `remark` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` bigint NOT NULL DEFAULT '0' COMMENT '0=未删除；非0=已删除（存本行id，保证编码可释放）',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_code` (`code`,`deleted`),
  KEY `idx_parent` (`parent_id`),
  KEY `idx_org` (`org_id`),
  KEY `idx_category` (`category`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='工程项目档案';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `md_supplier`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `md_supplier` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `code` varchar(40) COLLATE utf8mb4_unicode_ci NOT NULL,
  `name` varchar(120) COLLATE utf8mb4_unicode_ci NOT NULL,
  `short_name` varchar(60) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `org_id` bigint NOT NULL DEFAULT '1' COMMENT '所属组织',
  `org_name` varchar(80) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `is_shared` tinyint NOT NULL DEFAULT '0' COMMENT '是否集团共享',
  `mnemonic_code` varchar(40) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `supplier_category` varchar(80) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `region_category` varchar(80) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `is_customer` tinyint NOT NULL DEFAULT '0',
  `supplier_type` varchar(40) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `finance_org` varchar(80) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `tax_no` varchar(40) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `bank_name` varchar(80) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `bank_account` varchar(40) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `contact_name` varchar(40) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `contact_phone` varchar(20) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `address` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `pay_term` varchar(40) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '付款条件 如 月结30天',
  `default_tax` decimal(5,2) DEFAULT '13.00',
  `status` tinyint NOT NULL DEFAULT '1',
  `remark` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `created_by` bigint DEFAULT NULL,
  `deleted` bigint NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_code` (`code`,`deleted`),
  KEY `idx_org` (`org_id`),
  KEY `idx_shared` (`is_shared`),
  KEY `idx_md_supplier_org` (`org_id`),
  KEY `idx_md_supplier_shared` (`is_shared`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='供应商档案';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `md_supplier_org_scope`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `md_supplier_org_scope` (
  `supplier_id` bigint NOT NULL,
  `org_id` bigint NOT NULL,
  PRIMARY KEY (`supplier_id`,`org_id`),
  KEY `idx_org` (`org_id`,`supplier_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='供应商组织授权范围';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `md_warehouse`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `md_warehouse` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `code` varchar(40) COLLATE utf8mb4_unicode_ci NOT NULL,
  `name` varchar(80) COLLATE utf8mb4_unicode_ci NOT NULL,
  `warehouse_type` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'SECONDARY' COMMENT 'MAIN/SECONDARY',
  `org_id` bigint NOT NULL,
  `manager_id` bigint DEFAULT NULL COMMENT '负责人',
  `address` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `status` tinyint NOT NULL DEFAULT '1',
  `deleted` bigint NOT NULL DEFAULT '0' COMMENT '0=未删除；非0=已删除（存本行id，保证编码可释放）',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_code` (`code`,`deleted`),
  KEY `idx_org` (`org_id`),
  KEY `idx_wh_type` (`warehouse_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='仓库';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `md_warehouse_loc`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `md_warehouse_loc` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `warehouse_id` bigint NOT NULL,
  `code` varchar(40) COLLATE utf8mb4_unicode_ci NOT NULL,
  `name` varchar(80) COLLATE utf8mb4_unicode_ci NOT NULL,
  `status` tinyint NOT NULL DEFAULT '1',
  `deleted` tinyint NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_wh_code` (`warehouse_id`,`code`,`deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='库位';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `notif`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `notif` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint DEFAULT NULL COMMENT '接收人，与 role 二选一',
  `role_code` varchar(40) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '角色编码（推送给某角色）',
  `org_id` bigint DEFAULT NULL COMMENT '业务单据所属账套/组织，用于按角色广播时的账套隔离',
  `type` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'TODO' COMMENT 'TODO/INFO/WARN',
  `todo_kind` varchar(16) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '待办性质:APPROVAL=等我审批/确认,REWORK=退回要我改;NULL=升级前的存量数据',
  `title` varchar(500) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '标题(驳回意见会拼进来,故需 500 余量)',
  `content` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `biz_type` varchar(20) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `biz_id` bigint DEFAULT NULL,
  `biz_no` varchar(40) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `is_read` tinyint NOT NULL DEFAULT '0',
  `read_at` datetime DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_user_read` (`user_id`,`is_read`),
  KEY `idx_role` (`role_code`,`is_read`),
  KEY `idx_role_org` (`role_code`,`org_id`,`is_read`),
  KEY `idx_biz` (`biz_type`,`biz_no`),
  KEY `idx_notif_type_kind` (`type`,`todo_kind`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='消息/待办';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `notif_read`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `notif_read` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `notif_id` bigint NOT NULL,
  `user_id` bigint NOT NULL,
  `read_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_notif_user` (`notif_id`,`user_id`),
  KEY `idx_user_read` (`user_id`,`read_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='role todo personal read state';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `pp_request`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `pp_request` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `doc_no` varchar(40) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '采购计划单号 PP-日期-序号',
  `org_id` bigint NOT NULL,
  `link_id` bigint DEFAULT NULL COMMENT '来源提交链接',
  `warehouse_id` bigint NOT NULL COMMENT '参照库存的仓库(取自链接默认仓)',
  `applicant_name` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '提报人',
  `dept` varchar(80) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '提报部门',
  `status` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'SUBMITTED',
  `confirm_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `confirm_at` datetime DEFAULT NULL,
  `reject_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `reject_at` datetime DEFAULT NULL,
  `reject_reason` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `remark` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` tinyint NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_pp_request_no` (`doc_no`),
  KEY `idx_pp_request_org` (`org_id`,`deleted`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='采购计划单(计划，不动库存)';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `pp_request_line`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `pp_request_line` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `request_id` bigint NOT NULL,
  `line_no` int NOT NULL DEFAULT '1',
  `material_id` bigint NOT NULL,
  `demand_qty` decimal(18,3) NOT NULL DEFAULT '0.000' COMMENT '业务提报的需求数量',
  `final_qty` decimal(18,3) DEFAULT NULL COMMENT '采购确认的最终采购量(默认取建议采购量)',
  `remark` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_pp_req_line_req` (`request_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='采购计划明细';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `sys_dict`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_dict` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `type` varchar(40) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '字典类型',
  `code` varchar(40) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '字典编码',
  `label` varchar(80) COLLATE utf8mb4_unicode_ci NOT NULL,
  `sort` int NOT NULL DEFAULT '0',
  `status` tinyint NOT NULL DEFAULT '1',
  `remark` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_type_code` (`type`,`code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='数据字典';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `sys_doc_rule`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_doc_rule` (
  `doc_type` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '单据类型 PR/PO/RECV/INV/OREQ/OUT',
  `prefix` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '前缀',
  `pattern` varchar(80) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '规则 如 {company}-{prefix}-{YYYYMMDD}-{0000}',
  `current_no` bigint NOT NULL DEFAULT '0' COMMENT '最近生成流水',
  `reset_freq` varchar(10) COLLATE utf8mb4_unicode_ci DEFAULT 'DAILY' COMMENT 'DAILY/MONTHLY/YEARLY/NEVER',
  `last_reset` date DEFAULT NULL,
  PRIMARY KEY (`doc_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='单据编码规则';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `sys_doc_sequence`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_doc_sequence` (
  `doc_type` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '单据类型',
  `scope_key` varchar(40) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '编号范围：公司/账套编码',
  `serial_date` date NOT NULL COMMENT '流水日期',
  `current_no` bigint NOT NULL DEFAULT '0' COMMENT '当前流水',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`doc_type`,`scope_key`,`serial_date`),
  KEY `idx_scope_date` (`scope_key`,`serial_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='单据编码流水';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `sys_log`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_log` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint DEFAULT NULL,
  `username` varchar(40) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `action` varchar(80) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '动作描述',
  `module` varchar(40) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '所属模块',
  `doc_no` varchar(40) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '相关单据号',
  `ip` varchar(40) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `request_uri` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `request_body` longtext COLLATE utf8mb4_unicode_ci,
  `response` longtext COLLATE utf8mb4_unicode_ci,
  `duration_ms` int DEFAULT NULL,
  `success` tinyint DEFAULT '1',
  `error_msg` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_user` (`user_id`),
  KEY `idx_doc` (`doc_no`),
  KEY `idx_time` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='操作日志';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `sys_menu`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_menu` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `parent_id` bigint NOT NULL DEFAULT '0',
  `name` varchar(40) COLLATE utf8mb4_unicode_ci NOT NULL,
  `path` varchar(120) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '前端路由',
  `component` varchar(120) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '前端组件',
  `icon` varchar(40) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `perm_code` varchar(80) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '权限编码 如 pr.create',
  `type` varchar(10) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'MENU' COMMENT 'DIR目录 MENU菜单 BUTTON按钮',
  `sort` int NOT NULL DEFAULT '0',
  `visible` tinyint NOT NULL DEFAULT '1',
  `status` tinyint NOT NULL DEFAULT '1',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` tinyint NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_parent` (`parent_id`),
  KEY `idx_perm` (`perm_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='菜单/按钮';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `sys_org`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_org` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `parent_id` bigint NOT NULL DEFAULT '0' COMMENT '父节点ID',
  `code` varchar(40) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '组织编码',
  `name` varchar(80) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '组织名称',
  `type` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'DEPT' COMMENT '类型 GROUP/COMPANY/DEPT/AREA/BRANCH/TEAM',
  `approval_mode` varchar(20) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'approval mode: STANDARD/SIMPLE',
  `sort` int NOT NULL DEFAULT '0',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '1启用 0停用',
  `remark` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `created_by` bigint DEFAULT NULL,
  `updated_by` bigint DEFAULT NULL,
  `deleted` bigint NOT NULL DEFAULT '0' COMMENT '0=未删除；非0=已删除（存本行id，保证编码可释放）',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_parent_code` (`parent_id`,`code`,`deleted`),
  KEY `idx_parent` (`parent_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='组织架构（集团/公司/部门/片区/网点/班组）';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `sys_param`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_param` (
  `param_key` varchar(60) COLLATE utf8mb4_unicode_ci NOT NULL,
  `param_value` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL,
  `remark` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  PRIMARY KEY (`param_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='系统参数';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `sys_release_note`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_release_note` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `version_no` varchar(40) COLLATE utf8mb4_unicode_ci NOT NULL,
  `title` varchar(120) COLLATE utf8mb4_unicode_ci NOT NULL,
  `summary` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `content` text COLLATE utf8mb4_unicode_ci NOT NULL,
  `published_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `popup` tinyint NOT NULL DEFAULT '1',
  `status` tinyint NOT NULL DEFAULT '1',
  `sort` int NOT NULL DEFAULT '0',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` tinyint NOT NULL DEFAULT '0',
  `guide_json` text COLLATE utf8mb4_unicode_ci COMMENT '逐条带看条目(JSON)，为空则只显示正文',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_release_version` (`version_no`,`deleted`),
  KEY `idx_release_visible` (`status`,`deleted`,`published_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='系统更新说明';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `sys_release_note_read`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_release_note_read` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `version_no` varchar(40) COLLATE utf8mb4_unicode_ci NOT NULL,
  `user_id` bigint NOT NULL,
  `item_key` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `read_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_release_read` (`version_no`,`user_id`,`item_key`),
  KEY `idx_release_read_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='更新说明带看记录（谁看过哪一条）';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `sys_role`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_role` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `code` varchar(40) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '角色编码',
  `name` varchar(40) COLLATE utf8mb4_unicode_ci NOT NULL,
  `data_scope` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'SELF' COMMENT 'SELF/DEPT/COMPANY/GROUP/CUSTOM',
  `remark` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `status` tinyint NOT NULL DEFAULT '1',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` bigint NOT NULL DEFAULT '0' COMMENT '0=未删除；非0=已删除（存本行id，保证编码可释放）',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_code` (`code`,`deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='角色';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `sys_role_menu`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_role_menu` (
  `role_id` bigint NOT NULL,
  `menu_id` bigint NOT NULL,
  PRIMARY KEY (`role_id`,`menu_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='角色-菜单关联';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `sys_schema_migration`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_schema_migration` (
  `id` varchar(160) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `checksum` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `applied_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `sys_ticker`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_ticker` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `title` varchar(60) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '滚动条上显示的那一行',
  `content` varchar(2000) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '点开看的全文(选填)',
  `level` varchar(10) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'NORMAL' COMMENT 'NORMAL=普通(浅蓝) IMPORTANT=重要(橙色、排最前)',
  `start_date` date NOT NULL COMMENT '这天起显示',
  `end_date` date NOT NULL COMMENT '显示到这天(含),过了自动不显示',
  `all_orgs` tinyint NOT NULL DEFAULT '1' COMMENT '1=全部公司(含以后新开的) 0=只发给 sys_ticker_org 里的公司',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '1=启用 0=停用',
  `created_by` bigint DEFAULT NULL,
  `updated_by` bigint DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` tinyint NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_sys_ticker_live` (`deleted`,`status`,`start_date`,`end_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='顶栏滚动通知';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `sys_ticker_org`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_ticker_org` (
  `ticker_id` bigint NOT NULL,
  `org_id` bigint NOT NULL COMMENT '公司(账套根)',
  PRIMARY KEY (`ticker_id`,`org_id`),
  KEY `idx_sys_ticker_org_org` (`org_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='滚动通知发给哪些公司(all_orgs=0 时用)';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `sys_user`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_user` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `username` varchar(40) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '登录用户名',
  `password` varchar(100) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'BCrypt 加密',
  `name` varchar(40) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '姓名',
  `avatar` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `phone` varchar(20) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `email` varchar(80) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `org_id` bigint NOT NULL COMMENT '所属组织',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '1启用 0停用',
  `fail_count` int NOT NULL DEFAULT '0' COMMENT '连续登录失败次数',
  `locked_until` datetime DEFAULT NULL COMMENT '锁定到期时间',
  `last_login_at` datetime DEFAULT NULL,
  `last_login_ip` varchar(40) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `created_by` bigint DEFAULT NULL,
  `updated_by` bigint DEFAULT NULL,
  `deleted` bigint NOT NULL DEFAULT '0' COMMENT '0=未删除；非0=已删除（存本行id，保证账号名可释放）',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_username` (`username`,`deleted`),
  KEY `idx_org` (`org_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `sys_user_pref`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_user_pref` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL COMMENT '归谁(从登录态取,不信前端)',
  `pref_key` varchar(48) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'portal.layout / portal.favMods / portal.recentMods',
  `pref_value` text COLLATE utf8mb4_unicode_ci COMMENT 'JSON 文本,后端不解析内容',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_pref_user_key` (`user_id`,`pref_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户个人偏好(跟账号走,换电脑不丢)';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `sys_user_role`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_user_role` (
  `user_id` bigint NOT NULL,
  `role_id` bigint NOT NULL,
  PRIMARY KEY (`user_id`,`role_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户-角色关联';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `tool_task`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `tool_task` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `org_id` bigint NOT NULL COMMENT '归哪家公司(账套级)。查询一律按它过滤,不可为空',
  `tool_code` varchar(40) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '工具编码,如 fin-risk',
  `title` varchar(160) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '任务标题(如「财务风险预警-2026年第3号」)',
  `status` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING排队 RUNNING运行中 SUCCESS成功 FAILED失败',
  `params` mediumtext COLLATE utf8mb4_unicode_ci COMMENT '工具参数 JSON(用户填的表单)',
  `input_path` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '上传的原始文件相对路径;生成完置空并删文件',
  `output_files` text COLLATE utf8mb4_unicode_ci COMMENT '结果文件 JSON [{name,file,size}]',
  `error_msg` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '失败原因(给用户看的大白话,不回显堆栈)',
  `cost_ms` int NOT NULL DEFAULT '0' COMMENT '耗时毫秒',
  `created_by` bigint DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` tinyint NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_tool_org_created` (`org_id`,`status`,`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='工具箱任务(严格按公司隔离)';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `wf_cancel_request`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `wf_cancel_request` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `biz_type` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL,
  `biz_id` bigint NOT NULL,
  `biz_no` varchar(40) COLLATE utf8mb4_unicode_ci NOT NULL,
  `applicant_id` bigint NOT NULL COMMENT '撤销发起人',
  `origin_approver_id` bigint NOT NULL COMMENT '原审批人',
  `reason` varchar(500) COLLATE utf8mb4_unicode_ci NOT NULL,
  `status` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/APPROVED/REJECTED',
  `approver_comment` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `approved_at` datetime DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_origin` (`origin_approver_id`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='撤销审批申请';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `wf_instance`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `wf_instance` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `biz_type` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'PR/PO/RECV/INV/OREQ/OUT/CANCEL_REQ',
  `biz_id` bigint NOT NULL,
  `biz_no` varchar(40) COLLATE utf8mb4_unicode_ci NOT NULL,
  `status` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'PROCESSING' COMMENT 'PROCESSING/APPROVED/REJECTED/CANCELLED',
  `current_node` varchar(40) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '当前节点',
  `next_approver_id` bigint DEFAULT NULL COMMENT '下一个审批人',
  `next_approver_role` varchar(40) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '下一个审批角色',
  `template_id` bigint DEFAULT NULL COMMENT '提交时匹配到的审批模板',
  `nodes_json` text COLLATE utf8mb4_unicode_ci COMMENT '提交时审批节点快照',
  `applicant_id` bigint NOT NULL,
  `total_amt` decimal(18,2) DEFAULT NULL,
  `started_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `finished_at` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_biz` (`biz_type`,`biz_id`),
  KEY `idx_next` (`next_approver_id`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='审批流实例';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `wf_record`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `wf_record` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `instance_id` bigint NOT NULL,
  `biz_no` varchar(40) COLLATE utf8mb4_unicode_ci NOT NULL,
  `node` varchar(40) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `approver_id` bigint NOT NULL,
  `approver_name` varchar(40) COLLATE utf8mb4_unicode_ci NOT NULL,
  `action` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'SUBMIT/APPROVE/REJECT/CANCEL/RECALL',
  `comment` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_inst` (`instance_id`),
  KEY `idx_biz_no` (`biz_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='审批流操作记录';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `wf_template`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `wf_template` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `biz_type` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL,
  `name` varchar(80) COLLATE utf8mb4_unicode_ci NOT NULL,
  `approval_mode` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'STANDARD' COMMENT 'approval mode: STANDARD/SIMPLE',
  `condition_expr` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '触发条件 SpEL，如 #amt < 50000',
  `nodes_json` text COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '审批节点配置 JSON',
  `status` tinyint NOT NULL DEFAULT '1',
  `sort` int NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='审批流模板';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40103 SET TIME_ZONE=@OLD_TIME_ZONE */;

/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;
/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;
/*!40014 SET UNIQUE_CHECKS=@OLD_UNIQUE_CHECKS */;
/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
/*!40111 SET SQL_NOTES=@OLD_SQL_NOTES */;

