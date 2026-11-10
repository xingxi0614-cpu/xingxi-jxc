package com.group.scm.modules.system.controller;

import com.group.scm.common.Result;
import com.group.scm.common.SecurityUtil;
import com.group.scm.common.BizException;
import com.group.scm.common.aop.Idempotent;
import com.group.scm.common.aop.OpLog;
import com.group.scm.common.service.OrgScopeService;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.group.scm.modules.system.entity.SysOrg;
import com.group.scm.modules.system.mapper.SysOrgMapper;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@Tag(name = "组织架构")
@RestController
@RequestMapping("/sys/orgs")
@RequiredArgsConstructor
public class SysOrgController {

    private final SysOrgMapper orgMapper;
    private final JdbcTemplate jdbcTemplate;
    private final OrgScopeService orgScope;

    @GetMapping("/tree")
    public Result<List<OrgNode>> tree() {
        SecurityUtil.requireAccountAdmin();
        var list = orgMapper.selectList(null);
        if (!SecurityUtil.isAdmin()) {
            Set<Long> visibleIds = new HashSet<>(orgScope.visibleOrgIds());
            list = list.stream().filter(o -> visibleIds.contains(o.getId())).toList();
        }
        Map<Long, OrgNode> map = new HashMap<>();
        List<OrgNode> roots = new ArrayList<>();
        for (var o : list) {
            OrgNode n = new OrgNode();
            n.id = o.getId(); n.parentId = o.getParentId(); n.code = o.getCode();
            n.name = o.getName(); n.type = o.getType(); n.approvalMode = normalizeApprovalMode(o.getApprovalMode());
            n.sort = o.getSort();
            n.status = o.getStatus(); n.remark = o.getRemark();
            map.put(o.getId(), n);
        }
        for (var n : map.values()) {
            if (n.parentId == null || n.parentId == 0 || !map.containsKey(n.parentId)) roots.add(n);
            else map.get(n.parentId).children.add(n);
        }
        return Result.ok(roots);
    }

    @OpLog(module = "组织架构", action = "新建组织")
    @PostMapping
    public Result<?> create(@RequestBody SysOrg org) {
        SecurityUtil.requireAccountAdmin();
        normalize(org);
        assertAccountAdminCanCreate(org);
        assertParentExists(org.getParentId());
        assertParentVisible(org.getParentId());
        assertCodeUnique(org.getCode(), org.getParentId(), null);
        org.setId(null);
        try {
            orgMapper.insert(org);
        } catch (DuplicateKeyException e) {
            throw new BizException("同一上级组织下编码已存在，请更换编码");
        }
        return Result.ok();
    }

    @OpLog(module = "组织架构", action = "编辑组织")
    @PutMapping("/{id}")
    public Result<?> update(@PathVariable Long id, @RequestBody SysOrg org) {
        SecurityUtil.requireAccountAdmin();
        SysOrg current = orgMapper.selectById(id);
        if (current == null) throw new BizException(4040, "组织不存在");
        assertAccountAdminCanMaintain(current);
        normalize(org);
        assertAccountAdminCanCreate(org);
        if (id.equals(org.getParentId())) throw new BizException("上级组织不能选择自己");
        assertParentExists(org.getParentId());
        assertParentVisible(org.getParentId());
        if (org.getParentId() != null && isDescendant(org.getParentId(), id)) {
            throw new BizException("上级组织不能选择自己的下级组织");
        }
        assertCodeUnique(org.getCode(), org.getParentId(), id);
        // 计算审查 U31(2026-10-18):有仓库的公司不能改成部门——成本池按仓库所属组织建,改了会把一家公司的成本拆成两份
        if ("DEPT".equalsIgnoreCase(org.getType()) && !"DEPT".equalsIgnoreCase(current.getType())) {
            Integer wh = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM md_warehouse WHERE org_id = ? AND deleted = 0", Integer.class, id);
            if (wh != null && wh > 0) throw new BizException("该组织下还有 " + wh + " 个仓库，不能改成部门（仓库只能归属公司）");
        }
        org.setId(id);
        try {
            orgMapper.updateById(org);
        } catch (DuplicateKeyException e) {
            throw new BizException("同一上级组织下编码已存在，请更换编码");
        }
        return Result.ok();
    }

    @OpLog(module = "组织架构", action = "删除组织")
    @DeleteMapping("/{id}")
    public Result<?> delete(@PathVariable Long id) {
        SecurityUtil.requireAccountAdmin();
        SysOrg current = orgMapper.selectById(id);
        if (current == null) return Result.ok();
        assertAccountAdminCanMaintain(current);
        assertNotReferenced(id);
        // deleted 写本行 id 不写 1，理由同删除用户（uk_parent_code(parent_id, code, deleted) 会撞键），见迁移 20261001_001
        orgMapper.update(null, new UpdateWrapper<SysOrg>().eq("id", id).setSql("deleted = id"));
        return Result.ok();
    }

    @Idempotent
    @OpLog(module = "组织架构", action = "修改账套审批模式")
    @PutMapping("/{id}/approval-mode")
    public Result<?> updateApprovalMode(@PathVariable Long id, @RequestBody Map<String, String> body) {
        SecurityUtil.requireAccountAdmin();
        SysOrg current = orgMapper.selectById(id);
        if (current == null) throw new BizException(4040, "组织不存在");
        if (!"COMPANY".equalsIgnoreCase(current.getType())) {
            throw new BizException("只有公司/账套节点可以设置审批模式");
        }
        if (!SecurityUtil.isAdmin()) {
            orgScope.assertOrgVisible(id);
        }
        String approvalMode = normalizeApprovalMode(body == null ? null : body.get("approvalMode"));
        jdbcTemplate.update("""
                UPDATE sys_org
                SET approval_mode = ?, updated_at = NOW()
                WHERE id = ? AND deleted = 0
                """, approvalMode, id);
        return Result.ok();
    }

    private void assertAccountAdminCanCreate(SysOrg org) {
        if (SecurityUtil.isAdmin()) return;
        if ("GROUP".equalsIgnoreCase(org.getType()) || "COMPANY".equalsIgnoreCase(org.getType())) {
            throw new BizException(4003, "账套号/公司由集团管理员创建，账套管理员只能维护本账套下级组织");
        }
    }

    private void assertAccountAdminCanMaintain(SysOrg org) {
        if (SecurityUtil.isAdmin()) return;
        orgScope.assertOrgVisible(org.getId());
        if ("GROUP".equalsIgnoreCase(org.getType()) || "COMPANY".equalsIgnoreCase(org.getType())) {
            throw new BizException(4003, "账套管理员不能修改或删除集团/公司账套节点");
        }
    }

    private void assertParentVisible(Long parentId) {
        if (SecurityUtil.isAdmin()) return;
        if (parentId == null || parentId == 0) {
            throw new BizException(4003, "账套管理员不能新建根级组织");
        }
        orgScope.assertOrgVisible(parentId);
    }

    private void normalize(SysOrg org) {
        if (org == null) throw new BizException("组织信息不能为空");
        if (org.getCode() == null || org.getCode().isBlank()) throw new BizException("组织编码不能为空");
        if (org.getName() == null || org.getName().isBlank()) throw new BizException("组织名称不能为空");
        if (org.getType() == null || org.getType().isBlank()) throw new BizException("组织类型不能为空");
        org.setCode(org.getCode().trim());
        org.setName(org.getName().trim());
        org.setType(org.getType().trim());
        org.setApprovalMode("COMPANY".equalsIgnoreCase(org.getType()) ? normalizeApprovalMode(org.getApprovalMode()) : null);
        if (org.getParentId() == null) org.setParentId(0L);
        if (org.getSort() == null) org.setSort(0);
        if (org.getStatus() == null) org.setStatus(1);
    }

    private String normalizeApprovalMode(String value) {
        return "SIMPLE".equalsIgnoreCase(value == null ? null : value.trim()) ? "SIMPLE" : "STANDARD";
    }

    private void assertParentExists(Long parentId) {
        if (parentId == null || parentId == 0) return;
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_org WHERE id = ? AND deleted = 0",
                Integer.class, parentId);
        if (count == null || count == 0) throw new BizException("上级组织不存在，请刷新后重试");
    }

    private void assertCodeUnique(String code, Long parentId, Long excludeId) {
        Integer count = excludeId == null
                ? jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM sys_org WHERE parent_id = ? AND code = ? AND deleted = 0",
                        Integer.class, parentId, code)
                : jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM sys_org WHERE parent_id = ? AND code = ? AND id <> ? AND deleted = 0",
                        Integer.class, parentId, code, excludeId);
        if (count != null && count > 0) {
            throw new BizException("同一上级组织下编码已存在，请更换编码");
        }
    }

    private boolean isDescendant(Long candidateId, Long ancestorId) {
        Long cursor = candidateId;
        Set<Long> seen = new HashSet<>();
        while (cursor != null && cursor != 0 && seen.add(cursor)) {
            if (cursor.equals(ancestorId)) return true;
            Long parentId = jdbcTemplate.query("""
                    SELECT parent_id
                    FROM sys_org
                    WHERE id = ? AND deleted = 0
                    LIMIT 1
                    """, rs -> rs.next() ? rs.getLong("parent_id") : null, cursor);
            cursor = parentId;
        }
        return false;
    }

    private void assertNotReferenced(Long orgId) {
        Integer children = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_org WHERE parent_id = ? AND deleted = 0",
                Integer.class, orgId);
        if (children != null && children > 0) throw new BizException("该组织存在下级组织，不能删除");

        Integer users = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_user WHERE org_id = ? AND deleted = 0",
                Integer.class, orgId);
        if (users != null && users > 0) throw new BizException("该组织已有用户，不能删除");

        Integer warehouses = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM md_warehouse WHERE org_id = ? AND deleted = 0",
                Integer.class, orgId);
        if (warehouses != null && warehouses > 0) throw new BizException("该组织已有仓库，不能删除");
    }

    public static class OrgNode {
        public Long id;
        public Long parentId;
        public String code;
        public String name;
        public String type;
        public String approvalMode;
        public Integer sort;
        public Integer status;
        public String remark;
        public List<OrgNode> children = new ArrayList<>();
    }
}
