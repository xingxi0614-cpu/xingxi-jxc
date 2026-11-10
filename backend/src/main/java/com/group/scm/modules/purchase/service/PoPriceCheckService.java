package com.group.scm.modules.purchase.service;

import com.group.scm.common.BizException;
import com.group.scm.modules.purchase.entity.BizPoLine;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 采购单价防错：保存采购订单时，把每行含税单价和该物料的历史采购价比对。
 *
 * <h3>为什么要有它</h3>
 * 曾有公司把"含税单价"填进了"含税金额"栏(录入页主填的是金额)，
 * 系统倒算出几分钱的单价，按移动加权平均一路扩散到出库、调拨，存货少记约 18.7 万。
 * 两家不同的人犯同一个错，说明是录入设计问题，所以后端强制闸门，不只靠前端弹窗——
 * 手机端、小程序、Excel 导入都走同一个保存接口，谁也绕不过去。
 *
 * <h3>规则（2026-09-29 定）</h3>
 * <ul>
 *   <li>比历史价低 80% 以上(&lt; 参考价×0.2) 或高 5 倍以上(&gt; 参考价×5) 视为偏离；</li>
 *   <li>偏离行必须带制单人理由(priceCheckNote)才能保存，理由和当时的参考价落库给审批人看；</li>
 *   <li>从没买过的物料没有参考价，不提醒。</li>
 * </ul>
 *
 * <h3>参考价怎么算</h3>
 * 全集团同物料、已审批及之后状态的采购订单行，取最近 10 行；先剔除低于其中最高价 20% 的行
 * (防脏数据：曾有某物料 7 行里 5 行是错的，直接取中位数会把错价当成正常价)，
 * 再取剩下最近 5 行含税单价(金额÷数量)的中位数。物料是集团统一目录，只返回一个中位数，
 * 不暴露供应商或公司。
 */
@Service
@RequiredArgsConstructor
public class PoPriceCheckService {

    public static final BigDecimal LOW_RATIO = new BigDecimal("0.2");
    public static final BigDecimal HIGH_RATIO = new BigDecimal("5");
    /** 前端识别"需要填写价格理由"的错误码 */
    public static final int PRICE_CHECK_CODE = 4220;
    private static final int WINDOW = 10;
    private static final int MEDIAN_OF = 5;
    private static final int NOTE_MAX = 200;

    private final JdbcTemplate jdbcTemplate;

    public record PriceRef(BigDecimal refPrice, int samples) { }

    /** 批量取参考含税单价；excludePoId 用于修改订单时排除自己。 */
    public Map<Long, PriceRef> refs(Collection<Long> materialIds, Long excludePoId) {
        List<Long> ids = materialIds == null ? List.of()
                : materialIds.stream().filter(Objects::nonNull).distinct().limit(500).toList();
        if (ids.isEmpty()) return Map.of();
        String in = ids.stream().map(x -> "?").collect(Collectors.joining(","));
        List<Object> args = new ArrayList<>(ids);
        args.add(excludePoId == null ? -1L : excludePoId);
        args.add(WINDOW);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT material_id, unit_incl FROM (
                  SELECT l.material_id, l.amount / l.qty AS unit_incl,
                         ROW_NUMBER() OVER (PARTITION BY l.material_id ORDER BY l.id DESC) AS rn
                  FROM biz_po_line l JOIN biz_po p ON p.id = l.po_id AND p.deleted = 0
                  WHERE l.material_id IN (""" + in + """
                  ) AND p.status NOT IN ('DRAFT','PENDING','REJECTED','CANCELLED')
                    AND l.qty > 0 AND l.amount > 0 AND p.id <> ?
                ) t WHERE rn <= ? ORDER BY material_id, rn
                """, args.toArray());
        Map<Long, List<BigDecimal>> byMat = new LinkedHashMap<>();
        for (Map<String, Object> r : rows) {
            byMat.computeIfAbsent(((Number) r.get("material_id")).longValue(), k -> new ArrayList<>())
                    .add(new BigDecimal(r.get("unit_incl").toString()));
        }
        Map<Long, PriceRef> out = new HashMap<>();
        byMat.forEach((mat, recentFirst) -> {
            PriceRef ref = reference(recentFirst);
            if (ref != null) out.put(mat, ref);
        });
        return out;
    }

    /** 最近在前的历史单价 → 参考价；包级可见便于单测。 */
    static PriceRef reference(List<BigDecimal> recentFirst) {
        if (recentFirst == null || recentFirst.isEmpty()) return null;
        BigDecimal max = recentFirst.stream().max(Comparator.naturalOrder()).orElse(BigDecimal.ZERO);
        if (max.signum() <= 0) return null;
        BigDecimal floor = max.multiply(LOW_RATIO);
        List<BigDecimal> kept = recentFirst.stream().filter(p -> p.compareTo(floor) >= 0).limit(MEDIAN_OF)
                .sorted().toList();
        int n = kept.size();
        BigDecimal median = n % 2 == 1 ? kept.get(n / 2)
                : kept.get(n / 2 - 1).add(kept.get(n / 2)).divide(BigDecimal.valueOf(2), 4, RoundingMode.HALF_UP);
        return new PriceRef(median.setScale(4, RoundingMode.HALF_UP), n);
    }

    /** 含税单价相对参考价是否偏离过大。 */
    public static boolean deviates(BigDecimal unitIncl, BigDecimal ref) {
        if (unitIncl == null || ref == null || ref.signum() <= 0) return false;
        return unitIncl.compareTo(ref.multiply(LOW_RATIO)) < 0 || unitIncl.compareTo(ref.multiply(HIGH_RATIO)) > 0;
    }

    /**
     * 保存前的后端闸门。须在 calculateLines 之后调用(行金额、数量已校验)。
     * 偏离行：有理由 → 落参考价与理由；无理由 → 整单拒绝并逐行说明。
     * 未偏离行：清空两列，防止前端伪造"已确认"标记。
     */
    public void enforce(List<BizPoLine> lines, Long excludePoId) {
        if (lines == null || lines.isEmpty()) return;
        Map<Long, PriceRef> refs = refs(lines.stream().map(BizPoLine::getMaterialId).toList(), excludePoId);
        List<String> missing = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            BizPoLine line = lines.get(i);
            PriceRef ref = refs.get(line.getMaterialId());
            BigDecimal unitIncl = unitIncl(line);
            if (ref == null || !deviates(unitIncl, ref.refPrice())) {
                line.setPriceCheckRef(null);
                line.setPriceCheckNote(null);
                continue;
            }
            String note = line.getPriceCheckNote() == null ? "" : line.getPriceCheckNote().trim();
            if (note.isEmpty()) {
                missing.add("第 " + (i + 1) + " 行 " + materialName(line.getMaterialId())
                        + "：含税单价 " + money(unitIncl) + "，历史采购价约 " + money(ref.refPrice()));
                continue;
            }
            line.setPriceCheckRef(ref.refPrice());
            line.setPriceCheckNote(note.length() > NOTE_MAX ? note.substring(0, NOTE_MAX) : note);
        }
        if (!missing.isEmpty()) {
            throw new BizException(PRICE_CHECK_CODE, "以下明细单价与历史采购价相差过大，请核对是否把单价填进了\"含税金额\"栏；"
                    + "确属正常(特价、赠品等)请填写价格说明后再保存：\n" + String.join("\n", missing));
        }
    }

    private static BigDecimal unitIncl(BizPoLine line) {
        if (line.getQty() == null || line.getQty().signum() <= 0 || line.getAmount() == null) return null;
        return line.getAmount().divide(line.getQty(), 4, RoundingMode.HALF_UP);
    }

    private String materialName(Long materialId) {
        List<String> names = jdbcTemplate.queryForList(
                "SELECT CONCAT(code, ' ', name) FROM md_material WHERE id = ?", String.class, materialId);
        return names.isEmpty() ? "物料" + materialId : names.get(0);
    }

    private static String money(BigDecimal v) {
        return v == null ? "-" : v.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
