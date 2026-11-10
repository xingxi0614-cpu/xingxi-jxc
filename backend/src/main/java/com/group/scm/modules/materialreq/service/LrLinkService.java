package com.group.scm.modules.materialreq.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.group.scm.common.BizException;
import com.group.scm.common.SecurityUtil;
import com.group.scm.common.service.OrgScopeService;
import com.group.scm.modules.materialreq.entity.LrLink;
import com.group.scm.modules.materialreq.mapper.LrLinkMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/** 公司级领料申请提交链接服务（账套管理员维护）。 */
@Service
@RequiredArgsConstructor
public class LrLinkService {

    private final LrLinkMapper mapper;
    private final OrgScopeService orgScope;
    private final JdbcTemplate jdbc;

    public List<LrLink> list(Long orgId) { return list(orgId, null); }

    /**
     * @param linkType LR=领料提交链接 / SO=网点销售录单链接;传 null 看全部。
     *                 老数据没有这个字段值时按 LR 处理(2026-08-21 加的字段,默认值 'LR')。
     */
    public List<LrLink> list(Long orgId, String linkType) {
        QueryWrapper<LrLink> qw = new QueryWrapper<>();
        orgScope.applyOrgScope(qw, "org_id");
        if (orgId != null) qw.eq("org_id", orgId);
        if (linkType != null && !linkType.isBlank()) qw.eq("link_type", linkType);
        qw.orderByDesc("id");
        List<LrLink> rows = mapper.selectList(qw);
        enrich(rows);
        return rows;
    }

    public LrLink create(LrLink dto) {
        // 没指定类型的一律当领料链接,保持老行为
        if (dto.getLinkType() == null || dto.getLinkType().isBlank()) dto.setLinkType("LR");
        Long orgId = dto.getOrgId() != null ? dto.getOrgId() : SecurityUtil.orgId();
        if (orgId == null) throw new BizException("请指定公司");
        if (dto.getWarehouseId() == null) throw new BizException("请指定默认仓库");
        orgScope.assertOrgVisible(orgId);
        orgScope.assertWarehouseVisible(dto.getWarehouseId());
        LrLink e = new LrLink();
        e.setOrgId(orgId);
        e.setWarehouseId(dto.getWarehouseId());
        e.setLabel(dto.getLabel());
        e.setStatus(dto.getStatus() == null ? 1 : dto.getStatus());
        e.setToken(newToken());
        mapper.insert(e);
        enrich(List.of(e));
        return e;
    }

    public void update(Long id, LrLink dto) {
        LrLink e = mapper.selectById(id);
        if (e == null) throw new BizException("链接不存在");
        orgScope.assertOrgVisible(e.getOrgId());
        if (dto.getWarehouseId() != null) {
            orgScope.assertWarehouseVisible(dto.getWarehouseId());
            e.setWarehouseId(dto.getWarehouseId());
        }
        if (dto.getLabel() != null) e.setLabel(dto.getLabel());
        if (dto.getStatus() != null) e.setStatus(dto.getStatus());
        mapper.updateById(e);
    }

    /** 重置 token（旧链接立即失效）。 */
    public LrLink regenerate(Long id) {
        LrLink e = mapper.selectById(id);
        if (e == null) throw new BizException("链接不存在");
        orgScope.assertOrgVisible(e.getOrgId());
        e.setToken(newToken());
        mapper.updateById(e);
        return e;
    }

    public void delete(Long id) {
        LrLink e = mapper.selectById(id);
        if (e == null) return;
        orgScope.assertOrgVisible(e.getOrgId());
        mapper.deleteById(id);
    }

    private String newToken() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private void enrich(List<LrLink> rows) {
        if (rows == null || rows.isEmpty()) return;
        List<Long> orgIds = rows.stream().map(LrLink::getOrgId).filter(Objects::nonNull).distinct().toList();
        Map<Long, String> orgMap = LrPickerService.orgNames(jdbc, orgIds);
        List<Long> whIds = rows.stream().map(LrLink::getWarehouseId).filter(Objects::nonNull).distinct().toList();
        Map<Long, String> whMap = new HashMap<>();
        if (!whIds.isEmpty()) {
            String ph = whIds.stream().map(x -> "?").collect(Collectors.joining(","));
            jdbc.query("SELECT id, name FROM md_warehouse WHERE id IN (" + ph + ")",
                    (ResultSet rs) -> { whMap.put(rs.getLong("id"), rs.getString("name")); }, whIds.toArray());
        }
        rows.forEach(r -> {
            r.setOrgName(orgMap.get(r.getOrgId()));
            r.setWarehouseName(whMap.get(r.getWarehouseId()));
        });
    }
}
