package com.group.scm.common.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.group.scm.common.BizException;
import com.group.scm.common.SecurityUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class OrgScopeService {

    private final JdbcTemplate jdbcTemplate;

    /** 前端顶栏"查看公司"选中的公司(账套)组织 id；不带=看全部。只对能看多家公司的账号生效，见 narrowToViewCompany。 */
    public static final String VIEW_ORG_HEADER = "X-View-Org";

    /**
     * 当前请求能看到的组织范围 = 账号本身的权限范围，再按顶栏所选"查看公司"收窄。
     * 全站列表/报表/校验都从这里取范围，所以选公司只需在这一处生效。
     */
    public List<Long> visibleOrgIds() {
        Map<Long, OrgRef> orgs = loadOrgRefs();
        return narrowToViewCompany(permittedOrgIds(orgs), orgs);
    }

    /** 能否切换"查看公司"：只有本来就能看多家公司的账号(系统管理员 / 数据范围=全部或集团)。 */
    public boolean canSwitchViewCompany() {
        return SecurityUtil.isAdmin() || SecurityUtil.hasDataScope("ALL") || SecurityUtil.hasDataScope("GROUP");
    }

    /**
     * 顶栏选了某家公司时，本次请求实际收窄到的组织 id；没选(看全部)或账号不能切换时返回 null。
     * 给"系统管理员不走 visibleOrgIds 直接放行"的地方用(如基础资料列表)，让它们也跟着所选公司走。
     */
    public List<Long> viewCompanyOrgIds() {
        if (requestedViewOrgId() == null || !canSwitchViewCompany()) return null;
        return visibleOrgIds();
    }

    /**
     * 按所选公司收窄。安全要点(改这里前必读)：
     * ① 只做交集——结果永远是 permitted 的子集，只能缩小、绝不放大，所以不存在"带个请求头就看到别家"的越权；
     * ② 只对能看多家公司的账号生效；普通账号带了这个头直接忽略(他们的范围本来就只有自己账套)；
     * ③ 头不合法 / 指向自己权限外的公司 → 直接拒绝(4003)，不悄悄退回"看全部"——
     *    否则界面上显示"只看某一家公司"，实际出来的却是全部公司的数，比报错危险得多。
     */
    private List<Long> narrowToViewCompany(List<Long> permitted, Map<Long, OrgRef> orgs) {
        String raw = requestedViewOrgId();
        if (raw == null || !canSwitchViewCompany()) return permitted;
        long viewOrgId;
        try {
            viewOrgId = Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw new BizException(4003, "查看公司参数不合法，请在顶栏重新选择公司");
        }
        OrgRef view = orgs.get(viewOrgId);
        if (view == null || !permitted.contains(viewOrgId)) {
            throw new BizException(4003, "无权查看该公司或该公司已停用，请在顶栏重新选择公司");
        }
        OrgRef root = orgs.get(accountSetRoot(view, orgs));
        // 只接受"某家公司(或它下面的部门)"；集团总部这类不属于任何公司的组织不是合法选项
        if (root == null || !"COMPANY".equalsIgnoreCase(root.type())) {
            throw new BizException(4003, "所选组织不属于任何公司，请在顶栏重新选择公司");
        }
        Set<Long> company = new LinkedHashSet<>(descendants(root.id(), orgs));
        List<Long> narrowed = new ArrayList<>();
        for (Long id : permitted) if (company.contains(id)) narrowed.add(id);
        return narrowed;
    }

    /** 读请求头；定时任务等非网页请求的线程里没有请求对象，返回 null(=不收窄)。 */
    private String requestedViewOrgId() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs)) return null;
        String raw = attrs.getRequest().getHeader(VIEW_ORG_HEADER);
        return raw == null || raw.isBlank() ? null : raw.trim();
    }

    /**
     * 顶栏"查看公司"下拉 + 打印抬头要用的数据，范围严格限于账号本身权限内：
     * switchable=能否切换；companies=可选公司(账套)；orgCompany=每个组织 id → 它所属公司的名称
     * (打印单张单据时，按单据自己的归属组织印对应公司名，而不是登录人所在公司)。
     */
    public Map<String, Object> viewScope() {
        Map<Long, OrgRef> orgs = loadOrgRefs();
        List<Long> permitted = permittedOrgIds(orgs);
        Map<Long, String> names = new LinkedHashMap<>();
        Map<Long, Integer> sorts = new LinkedHashMap<>();
        jdbcTemplate.query("SELECT id, name, sort FROM sys_org WHERE deleted = 0 AND status = 1", rs -> {
            names.put(rs.getLong("id"), rs.getString("name"));
            sorts.put(rs.getLong("id"), rs.getInt("sort"));
        });
        Map<String, String> orgCompany = new LinkedHashMap<>();
        Map<Long, String> companies = new LinkedHashMap<>();
        for (Long id : permitted) {
            OrgRef ref = orgs.get(id);
            if (ref == null) continue;
            OrgRef root = orgs.get(accountSetRoot(ref, orgs));
            if (root == null || !"COMPANY".equalsIgnoreCase(root.type())) continue;   // 集团总部等不属于任何公司的组织
            orgCompany.put(String.valueOf(id), names.getOrDefault(root.id(), ""));
            companies.putIfAbsent(root.id(), names.getOrDefault(root.id(), ""));
        }
        boolean switchable = canSwitchViewCompany() && companies.size() > 1;
        List<Map<String, Object>> companyList = new ArrayList<>();
        if (switchable) {
            companies.entrySet().stream()
                    .sorted((a, b) -> {
                        int c = Integer.compare(sorts.getOrDefault(a.getKey(), 0), sorts.getOrDefault(b.getKey(), 0));
                        return c != 0 ? c : Long.compare(a.getKey(), b.getKey());
                    })
                    .forEach(e -> companyList.add(Map.of("id", e.getKey(), "name", e.getValue())));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("switchable", switchable);
        out.put("companies", companyList);
        out.put("orgCompany", orgCompany);
        return out;
    }

    /** 账号本身的权限范围(不看顶栏选了哪家公司)。 */
    private List<Long> permittedOrgIds(Map<Long, OrgRef> orgs) {
        Long currentOrgId = SecurityUtil.orgId();
        if (currentOrgId == null) return List.of();

        OrgRef current = orgs.get(currentOrgId);
        if (current == null) return List.of(currentOrgId);

        if (SecurityUtil.isAdmin() || SecurityUtil.hasDataScope("ALL")) {
            return new ArrayList<>(orgs.keySet());
        }

        if (SecurityUtil.hasDataScope("GROUP")) {
            return descendants(groupRoot(current, orgs), orgs);
        }

        if (SecurityUtil.hasDataScope("ACCOUNT_SET") || SecurityUtil.hasDataScope("COMPANY")) {
            return descendants(accountSetRoot(current, orgs), orgs);
        }

        if (SecurityUtil.hasDataScope("DEPT")) {
            return descendants(current.id(), orgs);
        }

        return List.of(current.id());
    }

    public Long currentAccountSetOrgId() {
        Long currentOrgId = SecurityUtil.orgId();
        if (currentOrgId == null) return null;
        Map<Long, OrgRef> orgs = loadOrgRefs();
        OrgRef current = orgs.get(currentOrgId);
        if (current == null) return currentOrgId;
        return accountSetRoot(current, orgs);
    }

    /** 给定组织所属的账套（公司）id；部门、网点归到它上面的公司。不属于任何公司时返回自身。 */
    public Long accountSetOf(Long orgId) {
        if (orgId == null) return null;
        Map<Long, OrgRef> orgs = loadOrgRefs();
        OrgRef current = orgs.get(orgId);
        return current == null ? orgId : accountSetRoot(current, orgs);
    }

    /**
     * 单据和仓库必须属于同一家公司(计算审查 U11,2026-10-18)。
     * 多公司可见的账号以前能给 A 公司订单选 B 公司仓库:单据归 A,库存和成本却从 B 扣,两家账对不上。
     * "看得到"不等于"能跨公司记账",这里只认同一账套。
     */
    public void assertSameCompany(Long docOrgId, Long warehouseId, String docName) {
        if (docOrgId == null || warehouseId == null) return;
        Long whOrg = jdbcTemplate.queryForList("SELECT org_id FROM md_warehouse WHERE id = ?", Long.class, warehouseId)
                .stream().findFirst().orElse(null);
        if (whOrg == null) throw new BizException("仓库不存在");
        Map<Long, OrgRef> orgs = loadOrgRefs();
        OrgRef doc = orgs.get(docOrgId), wh = orgs.get(whOrg);
        if (doc == null || wh == null) return;
        OrgRef docCo = orgs.get(accountSetRoot(doc, orgs)), whCo = orgs.get(accountSetRoot(wh, orgs));
        // 只在两边都能归到具体公司时比较;集团总部这类不属于任何公司的组织没有"所属公司"可比
        if (docCo == null || whCo == null
                || !"COMPANY".equalsIgnoreCase(docCo.type()) || !"COMPANY".equalsIgnoreCase(whCo.type())) return;
        if (!java.util.Objects.equals(docCo.id(), whCo.id())) {
            throw new BizException(docName + "与所选仓库不属于同一家公司，不能跨公司出入库。请选择本公司的仓库");
        }
    }

    /** 给定组织所在账套（账套集根）下的全部组织 id，用于按账套范围查重等场景。 */
    public List<Long> accountSetOrgIds(Long orgId) {
        if (orgId == null) return List.of();
        Map<Long, OrgRef> orgs = loadOrgRefs();
        OrgRef current = orgs.get(orgId);
        if (current == null) return List.of(orgId);
        return descendants(accountSetRoot(current, orgs), orgs);
    }

    public <T> void applyOrgScope(QueryWrapper<T> qw, String column) {
        List<Long> orgIds = visibleOrgIds();
        if (orgIds.isEmpty()) {
            qw.apply("1 = 0");
            return;
        }
        qw.in(column, orgIds);
    }

    public void assertOrgVisible(Long orgId) {
        if (orgId == null) throw new BizException("单据缺少账套归属，禁止操作");
        if (!visibleOrgIds().contains(orgId)) {
            throw new BizException(4003, "无权访问其他账套的数据");
        }
    }

    public void assertWarehouseVisible(Long warehouseId) {
        if (warehouseId == null) throw new BizException("仓库不能为空");
        List<Long> orgIds = visibleOrgIds();
        if (orgIds.isEmpty()) throw new BizException(4003, "无权访问仓库");
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM md_warehouse
                WHERE id = ? AND deleted = 0 AND status = 1 AND org_id IN (%s)
                """.formatted(placeholders(orgIds.size())),
                Integer.class,
                withFirst(warehouseId, orgIds));
        if (count == null || count == 0) throw new BizException(4003, "无权访问其他账套的仓库");
    }

    /**
     * 供应商按「集团池」模型（2026-09-17 定恢复）：单据引用只要求档案存在且启用，
     * 不做账套隔离——各公司录入、全集团选用、全局唯一。客户不适用本方法，见 assertCustomerVisible。
     */
    public void assertSupplierVisible(Long supplierId) {
        if (supplierId == null) throw new BizException("供应商不能为空");
        assertMasterEnabled("md_supplier", supplierId, "供应商不存在或已停用");
    }

    /**
     * 客户（往来单位 is_customer=1）按账套隔离：客户多为个人，姓名/电话/地址不外泄。
     * 归属账套可见范围 + 集团共享(is_shared=1) + md_supplier_org_scope 存量授权记录。
     */
    public void assertCustomerVisible(Long customerId) {
        if (customerId == null) throw new BizException("客户不能为空");
        assertScopedMasterVisible("md_supplier", "md_supplier_org_scope", "supplier_id",
                customerId, "无权使用其他账套的客户");
    }

    public void assertMaterialVisible(Long materialId) {
        if (materialId == null) throw new BizException("物料不能为空");
        assertMasterEnabled("md_material", materialId, "物料不存在或已停用");
    }

    public void assertProjectVisible(Long projectId) {
        if (projectId == null) throw new BizException("工程项目不能为空");
        assertMasterVisible("""
                SELECT COUNT(*)
                FROM md_project
                WHERE id = ?
                  AND deleted = 0
                  AND status = 1
                  AND org_id IN (%s)
                """, projectId, "无权使用其他账套的工程项目");
    }

    public List<Long> visibleWarehouseIds() {
        List<Long> orgIds = visibleOrgIds();
        if (orgIds.isEmpty()) return List.of();
        return jdbcTemplate.queryForList("""
                SELECT id
                FROM md_warehouse
                WHERE deleted = 0 AND status = 1 AND org_id IN (%s)
                ORDER BY code, id
                """.formatted(placeholders(orgIds.size())), Long.class, orgIds.toArray());
    }

    private Map<Long, OrgRef> loadOrgRefs() {
        return jdbcTemplate.query("""
                        SELECT id, parent_id, type
                        FROM sys_org
                        WHERE deleted = 0 AND status = 1
                        """,
                rs -> {
                    java.util.LinkedHashMap<Long, OrgRef> map = new java.util.LinkedHashMap<>();
                    while (rs.next()) {
                        long id = rs.getLong("id");
                        long parentId = rs.getLong("parent_id");
                        String type = rs.getString("type");
                        map.put(id, new OrgRef(id, parentId, type));
                    }
                    return map;
                });
    }

    private Long accountSetRoot(OrgRef current, Map<Long, OrgRef> orgs) {
        OrgRef cursor = current;
        OrgRef company = "COMPANY".equalsIgnoreCase(current.type()) ? current : null;
        while (cursor.parentId() != null && cursor.parentId() != 0 && orgs.containsKey(cursor.parentId())) {
            cursor = orgs.get(cursor.parentId());
            if ("COMPANY".equalsIgnoreCase(cursor.type())) {
                company = cursor;
            }
        }
        return company == null ? current.id() : company.id();
    }

    private Long groupRoot(OrgRef current, Map<Long, OrgRef> orgs) {
        OrgRef cursor = current;
        Long rootId = current.id();
        while (cursor.parentId() != null && cursor.parentId() != 0 && orgs.containsKey(cursor.parentId())) {
            rootId = cursor.parentId();
            cursor = orgs.get(cursor.parentId());
        }
        return rootId;
    }

    private List<Long> descendants(Long rootId, Map<Long, OrgRef> orgs) {
        Set<Long> ids = new LinkedHashSet<>();
        ArrayDeque<Long> queue = new ArrayDeque<>();
        ids.add(rootId);
        queue.add(rootId);
        while (!queue.isEmpty()) {
            Long parentId = queue.removeFirst();
            for (OrgRef org : orgs.values()) {
                if (parentId.equals(org.parentId()) && ids.add(org.id())) {
                    queue.add(org.id());
                }
            }
        }
        return new ArrayList<>(ids);
    }

    private String placeholders(int size) {
        if (size <= 0) return "NULL";
        return String.join(",", java.util.Collections.nCopies(size, "?"));
    }

    private Object[] withFirst(Long first, List<Long> rest) {
        Object[] args = new Object[1 + rest.size()];
        args[0] = first;
        for (int i = 0; i < rest.size(); i++) args[i + 1] = rest.get(i);
        return args;
    }

    private Object[] withFirst(Long first, List<Long> firstRest, List<Long> secondRest) {
        Object[] args = new Object[1 + firstRest.size() + secondRest.size()];
        args[0] = first;
        int index = 1;
        for (Long id : firstRest) args[index++] = id;
        for (Long id : secondRest) args[index++] = id;
        return args;
    }

    /**
     * 客户按账套隔离（2026-08-24 恢复，2026-09-17 起仅客户适用）：归属本账套可见范围
     * OR 集团共享(is_shared=1) OR 被 scopeTable 单独授权给可见账套内的某个组织
     * （历史"批量授权给组织"功能虽已下线，已授权的存量数据仍要继续认，不能悄悄失效）。
     * 供应商/物料均为集团池，不走本方法。
     */
    private void assertScopedMasterVisible(String masterTable, String scopeTable, String scopeMasterColumn,
                                           Long id, String message) {
        List<Long> orgIds = visibleOrgIds();
        if (orgIds.isEmpty()) throw new BizException(4003, message);
        String orgPlaceholders = placeholders(orgIds.size());
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM %s m
                WHERE m.id = ?
                  AND m.deleted = 0
                  AND m.status = 1
                  AND (
                    m.is_shared = 1
                    OR m.org_id IN (%s)
                    OR EXISTS (
                        SELECT 1
                        FROM %s s
                        WHERE s.%s = m.id
                          AND s.org_id IN (%s)
                    )
                  )
                """.formatted(masterTable, orgPlaceholders, scopeTable, scopeMasterColumn, orgPlaceholders),
                Integer.class,
                withFirst(id, orgIds, orgIds));
        if (count == null || count == 0) throw new BizException(4003, message);
    }

    /**
     * 物料/供应商按「集团池」模型管理：单据引用只要求档案存在且启用，
     * 不做账套隔离——集团统一目录，各公司录入、全集团选用。客户不适用本方法，见 assertCustomerVisible。
     */
    private void assertMasterEnabled(String masterTable, Long id, String message) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM %s m
                WHERE m.id = ?
                  AND m.deleted = 0
                  AND m.status = 1
                """.formatted(masterTable),
                Integer.class,
                id);
        if (count == null || count == 0) throw new BizException(message);
    }

    private void assertMasterVisible(String sqlTemplate, Long id, String message) {
        List<Long> orgIds = visibleOrgIds();
        if (orgIds.isEmpty()) throw new BizException(4003, message);
        Integer count = jdbcTemplate.queryForObject(
                sqlTemplate.formatted(placeholders(orgIds.size())),
                Integer.class,
                withFirst(id, orgIds));
        if (count == null || count == 0) throw new BizException(4003, message);
    }

    private record OrgRef(Long id, Long parentId, String type) {}
}
