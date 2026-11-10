package com.group.scm.common;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;

import java.util.Map;
import java.util.Set;

/**
 * 列表排序：把前端传来的排序字段翻译成数据库列名，<b>只认真实存在的列</b>。
 *
 * <p><b>为什么必须白名单：</b>排序列名没法用 SQL 参数占位符（? 只能替值、不能替列名），
 * 一旦把前端传来的字符串直接拼进 ORDER BY 就是注入口子。所以这里只做「前端字段名 →
 * 已知列名」的查表映射，表里没有的一律忽略、回落到各列表原本的默认排序，
 * 任何时候都不会把用户输入拼进 SQL。</p>
 *
 * <p><b>为什么排序必须在数据库做：</b>列表是分页的（每页 20 条），
 * 前端排序只会对当前页那 20 条重排，用户以为是全量排序，实际拿到的是错的顺序——
 * 数据越多越离谱。所以一律走后端 ORDER BY。</p>
 *
 * <p><b>两种用法：</b>
 * {@link #apply} 手写映射，适合列名与实体对不上的特殊列表；
 * {@link #applyByEntity} 从实体自动推导（推荐），省去为每张表手写清单，
 * 且天然排除 {@code @TableField(exist = false)} 的前端计算字段——
 * 那些字段数据库里根本没有，本来也排不了。</p>
 */
public final class SortWhitelist {

    private SortWhitelist() {}

    /**
     * 金额类字段。对金额已被脱敏的角色（仓管），这些字段禁止排序——
     * 值都不给看了，却能靠排序反推出谁大谁小，等于把脱敏绕过去了。
     */
    public static final Set<String> COST_FIELDS = Set.of(
            "totalAmt", "taxAmt", "amount", "unitPrice", "totalAmtInclTax",
            "netAmt", "adjustAmt", "totalStockAmt", "totalRecvAmt", "totalDiffAmt");

    /** 当前登录人能否看金额：与各 Controller 的 hideCost 口径一致（仓管看不到）。 */
    public static Set<String> costExclusionForCurrentUser() {
        return SecurityUtil.hasRole("warehouse") ? COST_FIELDS : null;
    }


    /** 手写映射版：allowed 是「前端字段名 → 数据库列名」。 */
    public static boolean apply(QueryWrapper<?> qw, String field, String order, Map<String, String> allowed) {
        if (allowed == null) return false;
        return applyColumn(qw, field == null ? null : allowed.get(field.trim()), order);
    }

    /**
     * 自动推导版：从 MyBatis-Plus 的实体映射里找这个字段对应的真实列。
     *
     * <p>只有实体上映射到数据库列的属性才能排；前端拼出来的摘要、映射出来的名称、
     * 以及一切 {@code @TableField(exist = false)} 的字段都不在其中，会被忽略。</p>
     *
     * @param excludeFields 需要额外禁止排序的字段（如对当前角色已脱敏的金额列——
     *                      值都不给看了，就不该让人靠排序反推大小顺序）
     */
    public static boolean applyByEntity(QueryWrapper<?> qw, String field, String order,
                                        Class<?> entityClass, Set<String> excludeFields) {
        if (field == null || field.isBlank() || entityClass == null) return false;
        String prop = field.trim();
        if (excludeFields != null && excludeFields.contains(prop)) return false;
        TableInfo info = TableInfoHelper.getTableInfo(entityClass);
        if (info == null) return false;

        String column = null;
        if (prop.equals(info.getKeyProperty())) {
            column = info.getKeyColumn();
        } else {
            for (var f : info.getFieldList()) {
                if (f.getProperty().equals(prop)) { column = f.getColumn(); break; }
            }
        }
        return applyColumn(qw, column, order);
    }

    public static boolean applyByEntity(QueryWrapper<?> qw, String field, String order, Class<?> entityClass) {
        return applyByEntity(qw, field, order, entityClass, null);
    }

    /** 真正落到 ORDER BY 的地方：column 一定来自上面的查表结果，绝不是用户原样输入。 */
    private static boolean applyColumn(QueryWrapper<?> qw, String column, String order) {
        if (qw == null || column == null || column.isBlank()) return false;
        // 兜底防线：即便映射表被写错，也只允许「字母数字下划线」，杜绝任何形式的拼接注入
        if (!column.matches("[A-Za-z0-9_]+")) return false;
        boolean asc = order == null || !order.startsWith("desc");
        qw.orderBy(true, asc, column);
        // 日期/金额常有并列值，再按主键兜底，保证翻页顺序稳定、不会同一条跨页重复出现
        if (!"id".equalsIgnoreCase(column)) qw.orderByDesc("id");
        return true;
    }
}
