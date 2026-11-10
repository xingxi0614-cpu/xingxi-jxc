package com.group.scm.modules.master.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.group.scm.common.BizException;
import com.group.scm.modules.master.entity.MdOutTypeSubject;
import com.group.scm.modules.master.mapper.MdOutTypeSubjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 出库类型-会计科目映射(集团统一,后台可维护)。
 * 保存/删除后自动把启用中的出库类型回写到 sys_param `out.type.options`,让全站出库类型下拉与映射表始终一致。
 */
@Service
@RequiredArgsConstructor
public class OutTypeSubjectService {

    private final MdOutTypeSubjectMapper mapper;
    private final JdbcTemplate jdbc;

    /**
     * 🔴 2026-08-05 安全审计修复:演示账套的人不许改这张表。
     *
     * 这张表【没有 org_id,是全集团共用一份】—— 它决定所有公司的出库类型下拉,
     * 以及月末给外部账务系统的科目汇总怎么归集。而推广演示号 demo 恰好带 account_admin
     * 角色、密码固定且按设计发放给外部潜在客户,等于把全集团的记账口径交到外人手里。
     *
     * 为什么不是简单收紧到 requireAdmin():实际维护这张表的人
     * 通常就是 account_admin,收紧到 admin 会把正当使用的人挡在门外
     * (admin 一般只有一个账号)。所以按【演示账套】而不是【角色】设防。
     *
     * 同一模式见 MaterialMasterImportService:55-59 —— 那里的注释记着一次事故:
     * 演示账套曾把大批正式物料的属主抢成自己。演示账套碰全局数据这件事,这个项目栽过。
     */
    private void assertNotDemoOrg() {
        Long myOrg = com.group.scm.common.SecurityUtil.orgId();
        if (myOrg == null) return;                       // 拿不到账套的交给上层角色校验
        Long demoOrg = jdbc.queryForList(
                        "SELECT param_value FROM sys_param WHERE param_key = 'demo.org.id' LIMIT 1", String.class)
                .stream().findFirst().map(Long::valueOf).orElse(null);
        if (demoOrg != null && demoOrg.equals(myOrg)) {
            throw new BizException("演示账套不能修改「出库类型→会计科目」映射:这张表是全集团共用的记账口径,"
                    + "改动会影响所有公司。如需调整请用正式账号操作。");
        }
    }

    public List<MdOutTypeSubject> list() {
        return mapper.selectList(new QueryWrapper<MdOutTypeSubject>().orderByAsc("sort").orderByAsc("id"));
    }

    @Transactional(rollbackFor = Exception.class)
    public MdOutTypeSubject save(MdOutTypeSubject dto) {
        assertNotDemoOrg();
        if (dto.getOutType() == null || dto.getOutType().isBlank()) throw new BizException("请填写出库类型");
        if (dto.getSubject() == null || dto.getSubject().isBlank()) throw new BizException("请填写对应科目");
        String outType = dto.getOutType().trim();
        // 出库类型唯一(排除自身)
        Long dup = jdbc.queryForList("SELECT id FROM md_out_type_subject WHERE out_type = ? AND deleted = 0", Long.class, outType)
                .stream().findFirst().orElse(null);
        if (dup != null && !dup.equals(dto.getId())) throw new BizException("出库类型「" + outType + "」已存在");
        dto.setOutType(outType);
        dto.setSubject(dto.getSubject().trim());
        if (dto.getStatus() == null) dto.setStatus(1);
        if (dto.getSort() == null) dto.setSort(0);
        if (dto.getId() == null) mapper.insert(dto); else mapper.updateById(dto);
        syncOptions();
        return dto;
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        assertNotDemoOrg();
        MdOutTypeSubject e = mapper.selectById(id);
        if (e == null) return;
        mapper.deleteById(id);
        syncOptions();
    }

    /** 把启用中的出库类型(按 sort)回写到 sys_param out.type.options,保持下拉与映射表一致。 */
    private void syncOptions() {
        List<String> types = mapper.selectList(new QueryWrapper<MdOutTypeSubject>()
                        .eq("status", 1).orderByAsc("sort").orderByAsc("id"))
                .stream().map(MdOutTypeSubject::getOutType).collect(Collectors.toList());
        if (types.isEmpty()) return;
        String value = String.join(",", types);
        int n = jdbc.update("UPDATE sys_param SET param_value = ? WHERE param_key = 'out.type.options'", value);
        if (n == 0) {
            jdbc.update("INSERT INTO sys_param (param_key, param_value, remark) VALUES ('out.type.options', ?, '出库类型下拉(由出库类型科目映射维护)')", value);
        }
    }
}
