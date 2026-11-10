package com.group.scm.modules.toolbox.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.group.scm.common.BizException;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 证件号码校验:统一社会信用代码 / 居民身份证号(2026-10-20 工具箱收录)。
 *
 * <p><b>为什么收它</b>:审核供应商、客户、施工单位资料时,经常要核对营业执照上的信用代码、经办人身份证号有没有抄错。
 * 两种号码最后一位都是按国家标准算出来的校验码,抄错一位基本都能发现。GitHub 上常用的开源工具库(如 Hutool 的
 * CreditCodeUtil / IdcardUtil)用的就是这两套标准算法;这里按标准原文自己实现,不引入整个第三方库。</p>
 * <ul>
 *   <li>统一社会信用代码:GB 32100-2015,18 位,字符集 0-9 + 大写字母(不含 I O Z S V),第 18 位按 31 取模;</li>
 *   <li>居民身份证号:GB 11643-1999,18 位,前 17 位加权和按 11 取模,第 18 位 0-9 或 X。</li>
 * </ul>
 * <p><b>边界</b>:校验通过只说明号码格式和校验位对,不代表这家企业 / 这个人真实存在——要核真伪仍须查
 * 国家企业信用信息公示系统等官方渠道,结果里写明这一点。即时型,不建任务、不落文件、不写操作日志。</p>
 */
@Component
public class IdCodeCheckTool implements ToolRunner {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 信用代码字符集(值 = 下标 0..30) */
    static final String CREDIT_CHARS = "0123456789ABCDEFGHJKLMNPQRTUWXY";
    static final int[] CREDIT_WEIGHTS = {1, 3, 9, 27, 19, 26, 16, 17, 20, 29, 25, 13, 8, 24, 10, 30, 28};
    static final int[] ID_WEIGHTS = {7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2};
    static final String ID_CHECK = "10X98765432";

    @Override public String code() { return "id-check"; }
    @Override public String name() { return "证件号码校验"; }
    @Override public String desc() {
        return "核对统一社会信用代码、身份证号有没有抄错(按国家标准算校验位)。审核供应商、客户资料时用;通过不代表企业/个人真实存在。";
    }
    @Override public boolean instant() { return true; }
    @Override public String group() { return "审核分析"; }

    @Override
    public Map<String, Object> runInstant(String paramsJson) throws Exception {
        JsonNode p = JSON.readTree(paramsJson == null || paramsJson.isBlank() ? "{}" : paramsJson);
        String raw = p.hasNonNull("code") ? p.get("code").asText() : "";
        // 容忍粘贴时带进来的空格、横杠,小写的 x 也认
        String code = raw.replaceAll("[\\s\\-]", "").toUpperCase();
        if (code.isEmpty()) throw new BizException(4000, "请输入统一社会信用代码或身份证号");
        if (code.length() != 18) throw new BizException(4000, "位数不对：两种号码都是 18 位，这里是 " + code.length() + " 位");

        Map<String, Object> out = new LinkedHashMap<>();
        String idError = idCardError(code);
        if (idError == null) {
            out.put("号码类型", "居民身份证号");
            out.put("结论", "✓ 校验通过");
            out.put("出生日期", code.substring(6, 10) + "-" + code.substring(10, 12) + "-" + code.substring(12, 14));
            out.put("性别", (code.charAt(16) - '0') % 2 == 1 ? "男" : "女");
            out.put("地区码", code.substring(0, 6));
            out.put("说明", "只说明号码格式和校验位正确，不代表本人真实存在或证件有效。");
            return out;
        }
        String creditError = creditCodeError(code);
        if (creditError == null) {
            out.put("号码类型", "统一社会信用代码");
            out.put("结论", "✓ 校验通过");
            out.put("登记管理部门", dept(code.charAt(0)));
            out.put("机构类别", category(code.charAt(0), code.charAt(1)));
            out.put("登记地区码", code.substring(2, 8));
            out.put("说明", "只说明号码格式和校验位正确；企业是否真实存在、是否存续，请到国家企业信用信息公示系统查。");
            return out;
        }
        // 两种都不通过:按长相判断用户想输的是哪种,给那一种的原因
        boolean looksId = code.substring(0, 17).chars().allMatch(Character::isDigit) && isDate(code.substring(6, 14));
        out.put("号码类型", looksId ? "居民身份证号(看格式像)" : "统一社会信用代码(看格式像)");
        out.put("结论", "✗ " + (looksId ? idError : creditError));
        out.put("说明", "多半是抄错了某一位，请对照原件逐位核对。");
        return out;
    }

    /** 统一社会信用代码不通过的原因;通过返回 null */
    static String creditCodeError(String code) {
        for (int i = 0; i < 18; i++) {
            if (CREDIT_CHARS.indexOf(code.charAt(i)) < 0) {
                return "第 " + (i + 1) + " 位「" + code.charAt(i) + "」不是信用代码能用的字符(不会出现 I、O、Z、S、V)";
            }
        }
        int sum = 0;
        for (int i = 0; i < 17; i++) sum += CREDIT_CHARS.indexOf(code.charAt(i)) * CREDIT_WEIGHTS[i];
        int c = 31 - sum % 31;
        char expect = CREDIT_CHARS.charAt(c == 31 ? 0 : c);
        return expect == code.charAt(17) ? null : "校验位不对：按前 17 位算，最后一位应是「" + expect + "」，现在是「" + code.charAt(17) + "」";
    }

    /** 身份证号不通过的原因;通过返回 null */
    static String idCardError(String code) {
        if (!code.substring(0, 17).chars().allMatch(Character::isDigit)) return "前 17 位必须都是数字";
        char last = code.charAt(17);
        if (!Character.isDigit(last) && last != 'X') return "最后一位只能是数字或 X";
        if (!isDate(code.substring(6, 14))) return "第 7～14 位「" + code.substring(6, 14) + "」不是有效的出生日期";
        if (LocalDate.parse(code.substring(6, 14), DateTimeFormatter.BASIC_ISO_DATE).isAfter(LocalDate.now())) return "出生日期晚于今天";
        int sum = 0;
        for (int i = 0; i < 17; i++) sum += (code.charAt(i) - '0') * ID_WEIGHTS[i];
        char expect = ID_CHECK.charAt(sum % 11);
        return expect == last ? null : "校验位不对：按前 17 位算，最后一位应是「" + expect + "」，现在是「" + last + "」";
    }

    private static boolean isDate(String yyyymmdd) {
        try {
            LocalDate d = LocalDate.parse(yyyymmdd, DateTimeFormatter.ofPattern("uuuuMMdd").withResolverStyle(ResolverStyle.STRICT));
            return d.getYear() >= 1900;
        } catch (Exception e) { return false; }
    }

    private static String dept(char c) {
        return switch (c) {
            case '1' -> "机构编制";
            case '2' -> "外交";
            case '3' -> "司法行政";
            case '4' -> "文化";
            case '5' -> "民政";
            case '6' -> "旅游";
            case '7' -> "宗教";
            case '8' -> "工会";
            case '9' -> "市场监督管理(工商)";
            case 'A' -> "中央军委改革和编制办公室";
            case 'N' -> "农业";
            case 'Y' -> "其他";
            default -> "未知(" + c + ")";
        };
    }

    private static String category(char dept, char c) {
        if (dept == '9') return switch (c) { case '1' -> "企业"; case '2' -> "个体工商户"; case '3' -> "农民专业合作社"; default -> "其他(" + c + ")"; };
        if (dept == '1') return switch (c) { case '1' -> "机关"; case '2' -> "事业单位"; case '3' -> "中央编办直接管理机构编制的群众团体"; default -> "其他(" + c + ")"; };
        if (dept == '5') return switch (c) { case '1' -> "社会团体"; case '2' -> "民办非企业单位"; case '3' -> "基金会"; default -> "其他(" + c + ")"; };
        return "代码 " + c;
    }
}
