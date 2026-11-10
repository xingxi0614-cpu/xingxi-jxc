package com.group.scm.modules;

import cn.dev33.satoken.stp.StpUtil;
import com.group.scm.common.BizException;
import com.group.scm.common.service.OrgScopeService;
import com.group.scm.modules.purchase.service.PoService;
import com.group.scm.modules.sales.service.SoService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 跨账套数据隔离测试。
 *
 * <p>自带两套互不隶属的账套夹具(集团 G 下挂公司 A、公司 B，各带一个部门)，断言:
 * 账套 A 的用户看不到/用不了账套 B 的组织、仓库、单据，反之亦然。</p>
 *
 * <p>物料/供应商为「集团池」模型：任意账套均可选用全库已启用档案，单据提交只校验档案存在且启用。
 * 客户(往来单位 is_customer=1)按账套隔离：只能选归属本账套可见范围内的记录 + 集团共享(is_shared=1)
 * + md_supplier_org_scope 授权范围表里的存量授权(批量授权给组织的管理入口 UI 已下线，但已授权的数据继续生效)。</p>
 *
 * <p>所有业务服务都依赖 {@link OrgScopeService} 的 assertXxxVisible / applyOrgScope 做账套闸门，
 * 因此这里直接断言该闸门 + 一条文档级端到端(采购订单 detail)即可覆盖隔离面。</p>
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "RUN_DB_TESTS", matches = "true",
        disabledReason = "需要隔离 MySQL 测试库")
@DisplayName("跨账套数据隔离")
class AccountSetIsolationTest {

    @Autowired private OrgScopeService orgScope;
    @Autowired private PoService poService;
    @Autowired private SoService soService;
    @Autowired private JdbcTemplate jdbc;

    // 组织：集团 G → 公司 A/部门 A、公司 B/部门 B
    private static final long G = 991900, COMPANY_A = 991901, DEPT_A = 991902, COMPANY_B = 991903, DEPT_B = 991904;
    private static final long WH_A = 991910, WH_B = 991911;
    private static final long MAT_A = 991920, MAT_B = 991921, MAT_B_ALLOC_A = 991922, MAT_SHARED = 991923;
    private static final long SUP_A = 991930, SUP_B = 991931, SUP_B_ALLOC_A = 991932, SUP_SHARED = 991933;
    private static final long PO_B = 991940;
    private static final long CUST_A = 991960, CUST_B = 991961;

    @BeforeEach
    void seedTwoAccountSets() {
        // 清掉固定 id，保证每次幂等
        jdbc.update("DELETE FROM md_material_org_scope WHERE material_id IN (?,?,?,?)", MAT_A, MAT_B, MAT_B_ALLOC_A, MAT_SHARED);
        jdbc.update("DELETE FROM md_supplier_org_scope WHERE supplier_id IN (?,?,?,?)", SUP_A, SUP_B, SUP_B_ALLOC_A, SUP_SHARED);
        jdbc.update("DELETE FROM biz_po WHERE id = ?", PO_B);
        jdbc.update("DELETE FROM md_warehouse WHERE id IN (?,?)", WH_A, WH_B);
        jdbc.update("DELETE FROM md_material WHERE id IN (?,?,?,?)", MAT_A, MAT_B, MAT_B_ALLOC_A, MAT_SHARED);
        jdbc.update("DELETE FROM md_supplier WHERE id IN (?,?,?,?,?,?)", SUP_A, SUP_B, SUP_B_ALLOC_A, SUP_SHARED, CUST_A, CUST_B);
        jdbc.update("DELETE FROM sys_org WHERE id IN (?,?,?,?,?)", DEPT_A, DEPT_B, COMPANY_A, COMPANY_B, G);

        // 组织树
        org(G, 0, "ISO-G", "隔离测试集团", "GROUP");
        org(COMPANY_A, G, "ISO-CA", "账套A公司", "COMPANY");
        org(DEPT_A, COMPANY_A, "ISO-DA", "账套A部门", "DEPT");
        org(COMPANY_B, G, "ISO-CB", "账套B公司", "COMPANY");
        org(DEPT_B, COMPANY_B, "ISO-DB", "账套B部门", "DEPT");

        // 仓库（按 org_id 归属）
        warehouse(WH_A, "ISO-WHA", "A仓", DEPT_A);
        warehouse(WH_B, "ISO-WHB", "B仓", DEPT_B);

        // 物料：归属 A / 归属 B / 归属 B 且历史授权给 A / 集团共享（is_shared 列保留，新模型不再参与过滤）
        material(MAT_A, "ISO-MA", "A物料", COMPANY_A, 0);
        material(MAT_B, "ISO-MB", "B物料", COMPANY_B, 0);
        material(MAT_B_ALLOC_A, "ISO-MBA", "B物料授权A", COMPANY_B, 0);
        jdbc.update("INSERT INTO md_material_org_scope(material_id, org_id) VALUES (?,?)", MAT_B_ALLOC_A, DEPT_A);
        material(MAT_SHARED, "ISO-MS", "共享物料", COMPANY_B, 1);

        // 供应商：归属 A / 归属 B / 归属 B 且历史授权给 A(集团池下授权表不再影响选用) / 集团共享
        supplier(SUP_A, "ISO-SA", "A供应商", COMPANY_A, 0);
        supplier(SUP_B, "ISO-SB", "B供应商", COMPANY_B, 0);
        supplier(SUP_B_ALLOC_A, "ISO-SBA", "B供应商授权A", COMPANY_B, 0);
        jdbc.update("INSERT INTO md_supplier_org_scope(supplier_id, org_id) VALUES (?,?)", SUP_B_ALLOC_A, DEPT_A);
        supplier(SUP_SHARED, "ISO-SS", "共享供应商", COMPANY_B, 1);

        // 客户(个人)：归属 A / 归属 B，姓名+电话不应跨公司互见
        jdbc.update("""
                INSERT INTO md_supplier(id, code, name, org_id, is_shared, is_customer, contact_name, contact_phone, status, deleted)
                VALUES (?,?,?,?,0,1,?,?,1,0)
                """, CUST_A, "ISO-CUA", "A客户张三", COMPANY_A, "张三", "13800000001");
        jdbc.update("""
                INSERT INTO md_supplier(id, code, name, org_id, is_shared, is_customer, contact_name, contact_phone, status, deleted)
                VALUES (?,?,?,?,0,1,?,?,1,0)
                """, CUST_B, "ISO-CUB", "B客户李四", COMPANY_B, "李四", "13800000002");

        // 账套 B 的一张采购订单
        jdbc.update("""
                INSERT INTO biz_po (id, doc_no, supplier_id, warehouse_id, org_id, order_date, status)
                VALUES (?, 'ISO-PO-B-0001', ?, ?, ?, '2026-01-01', 'PENDING')
                """, PO_B, SUP_B, WH_B, DEPT_B);
    }

    @AfterEach
    void clearLogin() {
        try { if (StpUtil.isLogin()) StpUtil.logout(); } catch (Exception ignored) {}
    }

    private void org(long id, long parent, String code, String name, String type) {
        jdbc.update("INSERT INTO sys_org(id, parent_id, code, name, type, status, deleted) VALUES (?,?,?,?,?,1,0)",
                id, parent, code, name, type);
    }
    private void warehouse(long id, String code, String name, long orgId) {
        jdbc.update("INSERT INTO md_warehouse(id, code, name, warehouse_type, org_id, status, deleted) VALUES (?,?,?,'SECONDARY',?,1,0)",
                id, code, name, orgId);
    }
    private void material(long id, String code, String name, long orgId, int shared) {
        jdbc.update("INSERT INTO md_material(id, code, name, unit, org_id, is_shared, status, deleted) VALUES (?,?,?,'个',?,?,1,0)",
                id, code, name, orgId, shared);
    }
    private void supplier(long id, String code, String name, long orgId, int shared) {
        jdbc.update("INSERT INTO md_supplier(id, code, name, org_id, is_shared, status, deleted) VALUES (?,?,?,?,?,1,0)",
                id, code, name, orgId, shared);
    }

    /** 以某账套部门的普通用户(非管理员、账套数据范围)登录 */
    private void loginAsAccountSet(long uid, long deptOrgId) {
        if (StpUtil.isLogin()) StpUtil.logout();
        StpUtil.login(uid);
        StpUtil.getSession().set("roles", List.of("purchaser"));
        StpUtil.getSession().set("perms", List.of());
        StpUtil.getSession().set("dataScopes", List.of("ACCOUNT_SET"));
        StpUtil.getSession().set("orgId", deptOrgId);
    }
    private void loginAsA() { loginAsAccountSet(991950L, DEPT_A); }
    private void loginAsB() { loginAsAccountSet(991951L, DEPT_B); }

    @Test
    @DisplayName("ISO-ORG: 可见组织按账套隔离，互不串账套")
    void visibleOrgIsolation() {
        loginAsA();
        List<Long> aVisible = orgScope.visibleOrgIds();
        assertTrue(aVisible.contains(COMPANY_A) && aVisible.contains(DEPT_A), "A 应能看到本账套组织");
        assertFalse(aVisible.contains(COMPANY_B) || aVisible.contains(DEPT_B), "A 不应看到账套 B 的组织");
        assertThrows(BizException.class, () -> orgScope.assertOrgVisible(DEPT_B), "A 访问 B 的组织应被拒");
        assertThrows(BizException.class, () -> orgScope.assertOrgVisible(COMPANY_B));
        assertDoesNotThrow(() -> orgScope.assertOrgVisible(DEPT_A));

        loginAsB();
        List<Long> bVisible = orgScope.visibleOrgIds();
        assertTrue(bVisible.contains(COMPANY_B) && bVisible.contains(DEPT_B));
        assertFalse(bVisible.contains(COMPANY_A) || bVisible.contains(DEPT_A), "B 不应看到账套 A 的组织");
        assertThrows(BizException.class, () -> orgScope.assertOrgVisible(DEPT_A), "B 访问 A 的组织应被拒");
    }

    @Test
    @DisplayName("ISO-WH: 仓库按账套隔离")
    void warehouseIsolation() {
        loginAsA();
        assertDoesNotThrow(() -> orgScope.assertWarehouseVisible(WH_A));
        assertThrows(BizException.class, () -> orgScope.assertWarehouseVisible(WH_B), "A 不应使用 B 的仓库");

        loginAsB();
        assertDoesNotThrow(() -> orgScope.assertWarehouseVisible(WH_B));
        assertThrows(BizException.class, () -> orgScope.assertWarehouseVisible(WH_A), "B 不应使用 A 的仓库");
    }

    @Test
    @DisplayName("ISO-MAT: 物料为集团池——任意账套可选用全部启用物料")
    void materialPoolModel() {
        loginAsA();
        assertDoesNotThrow(() -> orgScope.assertMaterialVisible(MAT_A), "A 可用本账套物料");
        assertDoesNotThrow(() -> orgScope.assertMaterialVisible(MAT_B), "集团池下 A 也可选用 B 归属的物料");
        assertDoesNotThrow(() -> orgScope.assertMaterialVisible(MAT_B_ALLOC_A));
        assertDoesNotThrow(() -> orgScope.assertMaterialVisible(MAT_SHARED));
        assertThrows(BizException.class, () -> orgScope.assertMaterialVisible(999999L), "不存在的物料应被拒");

        loginAsB();
        assertDoesNotThrow(() -> orgScope.assertMaterialVisible(MAT_A), "集团池下 B 也可选用 A 归属的物料");
        assertDoesNotThrow(() -> orgScope.assertMaterialVisible(MAT_B));
    }

    @Test
    @DisplayName("ISO-SUP: 供应商为集团池——任意账套可选用全部启用供应商；客户仍按账套隔离")
    void supplierPoolModel() {
        loginAsA();
        assertDoesNotThrow(() -> orgScope.assertSupplierVisible(SUP_A), "A 可用本账套供应商");
        assertDoesNotThrow(() -> orgScope.assertSupplierVisible(SUP_B), "集团池下 A 也可选用 B 归属的供应商");
        assertDoesNotThrow(() -> orgScope.assertSupplierVisible(SUP_B_ALLOC_A));
        assertDoesNotThrow(() -> orgScope.assertSupplierVisible(SUP_SHARED));
        assertThrows(BizException.class, () -> orgScope.assertSupplierVisible(999999L), "不存在的供应商应被拒");

        loginAsB();
        assertDoesNotThrow(() -> orgScope.assertSupplierVisible(SUP_A), "集团池下 B 也可选用 A 归属的供应商");
        assertDoesNotThrow(() -> orgScope.assertSupplierVisible(SUP_B));
    }

    @Test
    @DisplayName("ISO-CUST2: 客户按账套隔离——A 不能把单据挂到 B 账套的客户")
    void customerAssertIsolation() {
        loginAsA();
        assertDoesNotThrow(() -> orgScope.assertCustomerVisible(CUST_A), "A 可用本账套客户");
        assertThrows(BizException.class, () -> orgScope.assertCustomerVisible(CUST_B), "A 不应用 B 的客户");

        loginAsB();
        assertDoesNotThrow(() -> orgScope.assertCustomerVisible(CUST_B));
        assertThrows(BizException.class, () -> orgScope.assertCustomerVisible(CUST_A), "B 不应用 A 的客户");
    }

    @Test
    @DisplayName("ISO-CUST: 客户(个人)联系方式按账套隔离——A 看不到 B 客户的姓名电话")
    void customerIsolation() {
        loginAsA();
        List<Long> idsA = soService.customers(null).stream().map(m -> ((Number) m.get("id")).longValue()).toList();
        assertTrue(idsA.contains(CUST_A), "A 应能选到本账套客户");
        assertFalse(idsA.contains(CUST_B), "A 不应看到 B 账套客户的联系方式");

        loginAsB();
        List<Map<String, Object>> rowsB = soService.customers(null);
        List<Long> idsB = rowsB.stream().map(m -> ((Number) m.get("id")).longValue()).toList();
        assertTrue(idsB.contains(CUST_B));
        assertFalse(idsB.contains(CUST_A), "B 不应看到 A 账套客户的联系方式");
    }

    @Test
    @DisplayName("ISO-DOC: 单据按账套隔离——A 看不到 B 的采购订单")
    void documentIsolation() {
        loginAsA();
        assertThrows(BizException.class, () -> poService.detail(PO_B), "A 不应看到账套 B 的采购订单");

        loginAsB();
        assertDoesNotThrow(() -> poService.detail(PO_B), "B 可以看到本账套的采购订单");
    }
}
