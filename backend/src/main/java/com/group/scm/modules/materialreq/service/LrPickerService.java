package com.group.scm.modules.materialreq.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.group.scm.common.BizException;
import com.group.scm.common.SecurityUtil;
import com.group.scm.common.service.OrgScopeService;
import com.group.scm.modules.materialreq.entity.LrPicker;
import com.group.scm.modules.materialreq.mapper.LrPickerMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/** 领料人档案服务（账套管理员维护，按账套隔离）。 */
@Service
@RequiredArgsConstructor
public class LrPickerService {

    private final LrPickerMapper mapper;
    private final OrgScopeService orgScope;
    private final JdbcTemplate jdbc;

    public List<LrPicker> list(Long orgId, String keyword) {
        QueryWrapper<LrPicker> qw = new QueryWrapper<>();
        orgScope.applyOrgScope(qw, "org_id");
        if (orgId != null) qw.eq("org_id", orgId);
        if (keyword != null && !keyword.isBlank()) qw.like("name", keyword.trim());
        qw.orderByDesc("id");
        List<LrPicker> rows = mapper.selectList(qw);
        enrichOrgName(rows);
        return rows;
    }

    /** 供「领料人下拉」用：本账套启用中的领料人。 */
    public List<LrPicker> enabledByOrg(Long orgId) {
        if (orgId == null) return List.of();
        QueryWrapper<LrPicker> qw = new QueryWrapper<>();
        qw.eq("org_id", orgId).eq("status", 1).orderByAsc("name");
        return mapper.selectList(qw);
    }

    public LrPicker create(LrPicker dto) {
        if (dto.getName() == null || dto.getName().isBlank()) throw new BizException("领料人姓名必填");
        Long orgId = dto.getOrgId() != null ? dto.getOrgId() : SecurityUtil.orgId();
        if (orgId == null) throw new BizException("请指定所属公司");
        orgScope.assertOrgVisible(orgId);
        LrPicker e = new LrPicker();
        e.setOrgId(orgId);
        e.setName(dto.getName().trim());
        e.setStatus(dto.getStatus() == null ? 1 : dto.getStatus());
        mapper.insert(e);
        return e;
    }

    public void update(Long id, LrPicker dto) {
        LrPicker e = mapper.selectById(id);
        if (e == null) throw new BizException("领料人不存在");
        orgScope.assertOrgVisible(e.getOrgId());
        if (dto.getName() != null && !dto.getName().isBlank()) e.setName(dto.getName().trim());
        if (dto.getStatus() != null) e.setStatus(dto.getStatus());
        mapper.updateById(e);
    }

    public void delete(Long id) {
        LrPicker e = mapper.selectById(id);
        if (e == null) return;
        orgScope.assertOrgVisible(e.getOrgId());
        mapper.deleteById(id);
    }

    private void enrichOrgName(List<LrPicker> rows) {
        if (rows == null || rows.isEmpty()) return;
        List<Long> ids = rows.stream().map(LrPicker::getOrgId).filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) return;
        Map<Long, String> map = orgNames(jdbc, ids);
        rows.forEach(r -> r.setOrgName(map.get(r.getOrgId())));
    }

    /** 公共工具：批量取 sys_org 名称。 */
    static Map<Long, String> orgNames(JdbcTemplate jdbc, List<Long> ids) {
        Map<Long, String> map = new HashMap<>();
        if (ids == null || ids.isEmpty()) return map;
        String ph = ids.stream().map(x -> "?").collect(Collectors.joining(","));
        jdbc.query("SELECT id, name FROM sys_org WHERE id IN (" + ph + ")",
                (ResultSet rs) -> { map.put(rs.getLong("id"), rs.getString("name")); }, ids.toArray());
        return map;
    }
}
