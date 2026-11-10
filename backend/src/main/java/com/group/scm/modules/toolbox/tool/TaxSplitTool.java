package com.group.scm.modules.toolbox.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.group.scm.common.BizException;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 价税分离 / 含税不含税互算。
 *
 * 【为什么单独做】开票、核对发票、算暂估、审合同都要来一遍,拿计算器按容易按错税率;
 * 而且"含税倒算不含税"要除以(1+税率),不少人会直接乘税率算成税额,差一截。
 *
 * 【口径】
 *  · 不含税额 = 含税额 ÷ (1 + 税率)
 *  · 税额     = 含税额 − 不含税额
 *  · 一律四舍五入到分;三个数保证 不含税 + 税额 = 含税(尾差调进税额,与开票口径一致)
 */
@Component
public class TaxSplitTool implements ToolRunner {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Override public String code() { return "tax-split"; }
    @Override public String name() { return "价税分离计算"; }
    @Override public String desc() {
        return "含税额↔不含税额互算,自动出税额。核对发票、算暂估、审合同都用得上,不用怕按错计算器。";
    }
    @Override public boolean instant() { return true; }
    @Override public String group() { return "常用"; }

    @Override
    public Map<String, Object> runInstant(String paramsJson) throws Exception {
        JsonNode p = JSON.readTree(paramsJson == null || paramsJson.isBlank() ? "{}" : paramsJson);
        String mode = p.hasNonNull("mode") ? p.get("mode").asText() : "fromGross";  // fromGross=按含税算
        BigDecimal rate = dec(p, "rate", "输入税率");
        BigDecimal amount = dec(p, "amount", "输入金额");

        // 税率允许写 13 或 0.13 两种,按常识判断:大于 1 就当成百分数
        BigDecimal r = rate.compareTo(BigDecimal.ONE) > 0
                ? rate.divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP) : rate;
        if (r.signum() < 0 || r.compareTo(BigDecimal.ONE) >= 0) {
            throw new BizException(4000, "税率不合理:" + rate.toPlainString());
        }

        BigDecimal gross, net, tax;
        if ("fromNet".equals(mode)) {
            net = amount.setScale(2, RoundingMode.HALF_UP);
            tax = net.multiply(r).setScale(2, RoundingMode.HALF_UP);
            gross = net.add(tax);
        } else {
            gross = amount.setScale(2, RoundingMode.HALF_UP);
            net = gross.divide(BigDecimal.ONE.add(r), 2, RoundingMode.HALF_UP);
            // 尾差调进税额,保证三个数加得平 —— 与开票系统口径一致
            tax = gross.subtract(net);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("税率", r.multiply(BigDecimal.valueOf(100)).stripTrailingZeros().toPlainString() + "%");
        out.put("含税金额", gross.toPlainString());
        out.put("不含税金额", net.toPlainString());
        out.put("税额", tax.toPlainString());
        out.put("核对", net.add(tax).compareTo(gross) == 0 ? "不含税 + 税额 = 含税 ✓" : "⚠️ 加不平,请检查");
        return out;
    }

    private BigDecimal dec(JsonNode p, String k, String hint) {
        String s = p.hasNonNull(k) ? p.get(k).asText().trim() : "";
        s = s.replace(",", "").replace("，", "").replace("%", "").replace("￥", "").trim();
        if (s.isEmpty()) throw new BizException(4000, "请" + hint);
        try {
            return new BigDecimal(s);
        } catch (Exception e) {
            throw new BizException(4000, "「" + s + "」不是有效数字");
        }
    }
}
