package com.group.scm.modules.system.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.group.scm.common.Result;
import com.group.scm.common.SecurityUtil;
import com.group.scm.common.aop.OpLog;
import com.group.scm.modules.system.entity.SysDocRule;
import com.group.scm.modules.system.entity.SysMenu;
import com.group.scm.modules.system.mapper.SysDocRuleMapper;
import com.group.scm.modules.system.mapper.SysMenuMapper;
import com.group.scm.modules.workflow.entity.WfTemplate;
import com.group.scm.modules.workflow.mapper.WfTemplateMapper;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.*;

@Tag(name = "系统配置")
@RestController
@RequestMapping
@RequiredArgsConstructor
public class SysConfigController {

    private final SysMenuMapper menuMapper;
    private final SysDocRuleMapper docRuleMapper;
    private final WfTemplateMapper wfTemplateMapper;
    private final JdbcTemplate jdbcTemplate;
    private static final Map<String, String> DEFAULT_PUBLIC_OPTIONS = Map.of(
            "out.type.options", "销售材料,工程领料,生活用料,运营维修领料,其他出库",
            "business.category.options", "工程类,运营类,增值销售类,其他"
    );

    @GetMapping("/sys/menus/tree")
    public Result<List<MenuNode>> menus() {
        SecurityUtil.requireAdmin();
        List<SysMenu> list = menuMapper.selectList(new QueryWrapper<SysMenu>()
                .eq("status", 1)
                .orderByAsc("parent_id")
                .orderByAsc("sort"));
        return Result.ok(buildMenuTree(list));
    }

    @GetMapping("/sys/params")
    public Result<List<Map<String, Object>>> params() {
        SecurityUtil.requireAdmin();
        return Result.ok(jdbcTemplate.queryForList(
                "SELECT param_key, param_value, remark FROM sys_param ORDER BY param_key"));
    }

    @GetMapping("/sys/options/{key}")
    public Result<List<String>> options(@PathVariable String key) {
        if (!DEFAULT_PUBLIC_OPTIONS.containsKey(key)) {
            return Result.ok(List.of());
        }
        String value = jdbcTemplate.query("""
                        SELECT param_value
                        FROM sys_param
                        WHERE param_key = ?
                        """,
                rs -> rs.next() ? rs.getString(1) : DEFAULT_PUBLIC_OPTIONS.get(key),
                key);
        return Result.ok(splitOptions(value));
    }

    @OpLog(module = "系统参数", action = "修改系统参数")
    @PutMapping("/sys/params/{key}")
    public Result<?> saveParam(@PathVariable String key, @RequestBody Map<String, String> body) {
        SecurityUtil.requireAdmin();
        String value = body == null ? null : body.get("paramValue");
        String remark = body == null ? null : body.get("remark");
        if (value == null || value.isBlank()) return Result.fail("参数值不能为空");
        jdbcTemplate.update("""
                        INSERT INTO sys_param (param_key, param_value, remark)
                        VALUES (?, ?, ?)
                        ON DUPLICATE KEY UPDATE param_value = VALUES(param_value), remark = VALUES(remark)
                        """,
                key, value.trim(), remark);
        return Result.ok();
    }

    @GetMapping("/master/doc-rules")
    public Result<List<SysDocRule>> docRules() {
        SecurityUtil.requireAdmin();
        return Result.ok(docRuleMapper.selectList(new QueryWrapper<SysDocRule>().orderByAsc("doc_type")));
    }

    @GetMapping("/master/workflows")
    public Result<List<WfTemplate>> workflows() {
        SecurityUtil.requireAdmin();
        return Result.ok(wfTemplateMapper.selectList(new QueryWrapper<WfTemplate>().orderByAsc("biz_type").orderByAsc("sort")));
    }

    private List<String> splitOptions(String value) {
        if (value == null || value.isBlank()) return List.of();
        return Arrays.stream(value.split("[,，\\n]"))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .distinct()
                .toList();
    }

    private List<MenuNode> buildMenuTree(List<SysMenu> menus) {
        Map<Long, MenuNode> map = new LinkedHashMap<>();
        List<MenuNode> roots = new ArrayList<>();
        for (SysMenu menu : menus) {
            MenuNode node = new MenuNode();
            node.id = menu.getId();
            node.parentId = menu.getParentId();
            node.name = menu.getName();
            node.path = menu.getPath();
            node.component = menu.getComponent();
            node.permCode = menu.getPermCode();
            node.type = menu.getType();
            node.sort = menu.getSort();
            map.put(node.id, node);
        }
        for (MenuNode node : map.values()) {
            if (node.parentId == null || node.parentId == 0 || !map.containsKey(node.parentId)) {
                roots.add(node);
            } else {
                map.get(node.parentId).children.add(node);
            }
        }
        return roots;
    }

    public static class MenuNode {
        public Long id;
        public Long parentId;
        public String name;
        public String path;
        public String component;
        public String permCode;
        public String type;
        public Integer sort;
        public List<MenuNode> children = new ArrayList<>();
    }
}
