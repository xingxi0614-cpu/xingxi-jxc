package com.group.scm.modules.sales.service;

import com.group.scm.modules.sales.entity.BizSoLine;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** 未审批出库只占订单额度，不预占库存；写入方持有订单头行锁后再读取。 */
@Service
@RequiredArgsConstructor
public class SalesOutAvailability {
    private final JdbcTemplate jdbc;

    public Map<Long, BigDecimal> reserved(Long soId, Long excludeOutId) {
        return jdbc.query("""
                SELECT l.source_so_line_id, SUM(l.qty) reserved_qty
                FROM biz_sale_out o JOIN biz_sale_out_line l ON l.out_id=o.id
                WHERE o.source_so_id=? AND o.deleted=0 AND o.status IN ('DRAFT','PENDING')
                  AND (? IS NULL OR o.id<>?)
                GROUP BY l.source_so_line_id
                """, (rs, n) -> Map.entry(rs.getLong(1), rs.getBigDecimal(2)),
                soId, excludeOutId, excludeOutId).stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    public void attach(Long soId, List<BizSoLine> lines) {
        Map<Long, BigDecimal> reserved = reserved(soId, null);
        for (BizSoLine line : lines) {
            line.setReservedOutQty(reserved.getOrDefault(line.getId(), BigDecimal.ZERO));
            line.setAvailableOutQty(line.getQty().subtract(zero(line.getOutQty()))
                    .subtract(line.getReservedOutQty()).max(BigDecimal.ZERO));
        }
    }

    public static BigDecimal zero(BigDecimal n) { return n == null ? BigDecimal.ZERO : n; }
}
