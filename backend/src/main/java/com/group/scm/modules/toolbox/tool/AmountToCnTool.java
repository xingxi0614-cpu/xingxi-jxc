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
 * 金额转中文大写(支票、合同、报销单、正式文件都要用)。
 *
 * 【为什么值得单独做一个工具】写错一个字整张支票作废;网上找的转换器不敢往里贴公司金额。
 * 这里按人民银行《正确填写票据和结算凭证的基本规定》实现,规则都写在注释里,可以逐条核对。
 *
 * 【关键规则】(容易写错的都在这)
 *  · 数位:零壹贰叁肆伍陆柒捌玖 / 拾佰仟万亿
 *  · 中间连续的零只写一个「零」;末尾的零不写
 *  · 「万」「亿」位即使本节全为零也要保留单位(如 1000_0000 = 壹仟万元整)
 *  · 到元为止没有角分 → 末尾加「整」;有角无分 → 角后面加「整」;有分 → 不加「整」
 *  · 金额为零 → 零元整
 *  · 负数 → 前面加「负」
 */
@Component
public class AmountToCnTool implements ToolRunner {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final char[] DIGITS = "零壹贰叁肆伍陆柒捌玖".toCharArray();
    /** 节内单位:个十百千 */
    private static final String[] UNIT_IN = {"", "拾", "佰", "仟"};
    /** 节单位:个 万 亿 万亿 */
    private static final String[] UNIT_SEC = {"", "万", "亿", "万亿"};

    @Override public String code() { return "amount-cn"; }
    @Override public String name() { return "金额大写转换"; }
    @Override public String desc() {
        return "数字金额转中文大写(支票、合同、报销单用)。按人民银行票据填写规定,零和整的处理都按规范来。";
    }
    @Override public boolean instant() { return true; }
    @Override public String group() { return "常用"; }

    @Override
    public Map<String, Object> runInstant(String paramsJson) throws Exception {
        JsonNode p = JSON.readTree(paramsJson == null || paramsJson.isBlank() ? "{}" : paramsJson);
        String raw = p.hasNonNull("amount") ? p.get("amount").asText().trim() : "";
        if (raw.isEmpty()) throw new BizException(4000, "请输入金额");
        // 容忍用户粘进来带千分位逗号、带￥、带空格
        raw = raw.replace(",", "").replace("，", "").replace("￥", "").replace("¥", "").trim();

        BigDecimal amt;
        try {
            amt = new BigDecimal(raw);
        } catch (Exception e) {
            throw new BizException(4000, "「" + raw + "」不是有效的数字金额");
        }
        // 超过万亿位就不转了 —— 与其给个错的,不如说清楚做不了
        if (amt.abs().compareTo(new BigDecimal("9999999999999.99")) > 0) {
            throw new BizException(4000, "金额超过万亿位,本工具不支持");
        }
        // 银行家舍入不适用于金额:用四舍五入到分
        BigDecimal rounded = amt.setScale(2, RoundingMode.HALF_UP);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("输入金额", rounded.toPlainString());
        out.put("中文大写", toChinese(rounded));
        out.put("提示", raw.equals(rounded.toPlainString()) ? "" : "已按四舍五入保留到分");
        return out;
    }

    /** 主转换 */
    static String toChinese(BigDecimal v) {
        boolean neg = v.signum() < 0;
        BigDecimal abs = v.abs();
        long yuan = abs.longValue();                                   // 元
        int fenAll = abs.subtract(new BigDecimal(yuan))
                .multiply(BigDecimal.valueOf(100))
                .setScale(0, RoundingMode.HALF_UP).intValue();         // 角分合起来的分数
        int jiao = fenAll / 10, fen = fenAll % 10;

        StringBuilder sb = new StringBuilder();
        if (neg) sb.append('负');

        if (yuan == 0 && fenAll == 0) return sb.append("零元整").toString();

        if (yuan > 0) {
            sb.append(intToChinese(yuan)).append('元');
        } else {
            // 不足一元:规范写法是「零元」开头,免得被人在前面加字
            sb.append("零元");
        }

        if (jiao == 0 && fen == 0) {
            sb.append('整');
        } else {
            // 元有值、角为零但分有值 → 中间要补「零」(如 壹元零伍分)
            if (jiao == 0) {
                sb.append('零').append(DIGITS[fen]).append('分');
            } else {
                sb.append(DIGITS[jiao]).append('角');
                if (fen == 0) sb.append('整');
                else sb.append(DIGITS[fen]).append('分');
            }
        }
        return sb.toString();
    }

    /**
     * 整数部分转大写。按 4 位一节处理,节间加 万/亿。
     *
     * 【补零规则 —— 这里最容易写错,单测已覆盖】
     * 只有在"低位节还有非零内容"的前提下才补零,分两种情况:
     *   ① 中间整节为零:如 1_0000_0001 → 壹亿零壹(中间那个万节全零 → 补一个零)
     *   ② 本节高位不足:如 1_0203 → 壹万零贰佰零叁(0203 的千位是 0 → 补零)
     * 反过来,10000 = 壹万元整 —— 低位节全是零,【绝不能】写成"壹万零元"。
     * (第一版就是漏了"低位有没有内容"这个前提,10000 被写成「壹万零元整」,单测抓出来的)
     */
    private static String intToChinese(long n) {
        if (n == 0) return "零";
        StringBuilder sb = new StringBuilder();
        int sec = 0;                     // 第几节(0=个 1=万 2=亿 3=万亿)
        boolean lowerHasValue = false;   // 已处理过的低位节里有没有非零内容
        boolean gapZero = false;         // 低位与本节之间隔着整节的零
        boolean lowerShort = false;      // 【紧邻的低位节】是否不足四位(如 0630)

        while (n > 0) {
            int part = (int) (n % 10000);
            if (part == 0) {
                if (lowerHasValue) gapZero = true;   // 只有低位有内容,这个空节才需要补零
            } else {
                // 补零的判断点在【高位节】这一轮:看它下面那节是不是缺了高位。
                // ⚠️ 不能在处理低位节时判断 —— 那时还不知道上面有没有节
                //    (20250630 的 0630 是最低节,处理它时无从得知后面还有个"万"节。
                //     第二版就是栽在这里,单测抓出来的)
                if (lowerHasValue && (gapZero || lowerShort)) sb.insert(0, '零');
                sb.insert(0, sectionToChinese(part) + UNIT_SEC[sec]);
                gapZero = false;
            }
            // 记录本节情况,供上一轮(更高节)判断用
            if (part != 0) {
                lowerHasValue = true;
                lowerShort = part < 1000;     // 本节高位缺位 → 上面那节接下来要补零
            }
            n /= 10000;
            sec++;
        }
        return sb.toString();
    }

    /** 一节(0-9999)转大写,不带节单位 */
    private static String sectionToChinese(int part) {
        StringBuilder sb = new StringBuilder();
        boolean zeroPending = false;
        boolean started = false;
        for (int i = 3; i >= 0; i--) {
            int d = (part / (int) Math.pow(10, i)) % 10;
            if (d == 0) {
                if (started) zeroPending = true;
            } else {
                if (zeroPending) { sb.append('零'); zeroPending = false; }
                sb.append(DIGITS[d]).append(UNIT_IN[i]);
                started = true;
            }
        }
        return sb.toString();
    }
}
