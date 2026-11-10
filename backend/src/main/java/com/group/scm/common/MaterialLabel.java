package com.group.scm.common;

import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;

/**
 * 报错提示里的物料叫法：统一成「编码 名称（规格）」。
 *
 * 为什么要有它：以前"库存不足"等提示直接拼数据库内部 id（如"物料 1234"），
 * 仓管看不懂缺的是哪个料，这是实际使用中出现最多的一条报错。
 * 只用于拼提示文字：查不到或查询出错时退回"物料#id"，绝不能因为取名字失败把原本的业务报错盖掉。
 */
public final class MaterialLabel {

    private MaterialLabel() {}

    public static String of(JdbcTemplate jdbc, Long materialId) {
        if (materialId == null) return "物料";
        try {
            List<String> names = jdbc.queryForList(
                    "SELECT CONCAT(code, ' ', name, IF(spec IS NULL OR spec = '', '', CONCAT('（', spec, '）'))) "
                            + "FROM md_material WHERE id = ?", String.class, materialId);
            if (names != null && !names.isEmpty() && names.get(0) != null) return names.get(0);
        } catch (Exception ignored) {
            // 取名字失败不影响原报错
        }
        return "物料#" + materialId;
    }

    /** 数量去掉无意义的尾零：5.0000 → 5 */
    public static String qty(BigDecimal v) {
        if (v == null) return "0";
        return v.signum() == 0 ? "0" : v.stripTrailingZeros().toPlainString();
    }
}
