package com.group.scm.common;

import cn.dev33.satoken.context.SaHolder;

/**
 * 登录终端类型识别（手机 / 电脑）。
 *
 * <p><b>为什么要分终端：</b>系统原先是"一个账号只能一处在线"，仓管在电脑上开着单子、
 * 掏出手机审批一下，电脑就被挤下线了，手机 App 因此没法用。分成 APP / PC 两类之后：
 * 同一个人手机、电脑可以各在线一个，互不干扰；<b>同类终端仍然互斥</b>——换台电脑登录
 * 照样把前一台挤掉，"一个账号多人共用"这条防线保留。</p>
 *
 * <p><b>为什么只看 User-Agent、不看前端传的字段：</b>终端类型决定了会话能活多久
 * （手机 2 小时、电脑 30 分钟），属于安全口径。前端传来的任何字段都可能被伪造，
 * 这里只用后端自己能观察到的请求头判断，前端说什么都不采信。UA 同样可以伪造，
 * 但伪造的收益仅仅是让自己的会话多活一会儿，且前提是密码已经正确，风险可接受。</p>
 */
public final class ClientDeviceUtil {

    /** 手机端（安卓 App 壳，以及手机浏览器）。 */
    public static final String DEVICE_APP = "APP";
    /** 电脑端浏览器。 */
    public static final String DEVICE_PC = "PC";

    /** 安卓壳在 WebView 的 UA 末尾追加的自有标记，见 mobile/android MainActivity。 */
    private static final String APP_SHELL_FLAG = "ScmApp";

    private ClientDeviceUtil() {
    }

    /**
     * 判断当前请求来自哪类终端。取不到请求上下文（定时任务、单元测试）时按电脑端处理，
     * 也就是取更严格的那一档超时——拿不准时不放宽，是本项目的一贯口径。
     */
    public static String currentDevice() {
        try {
            return resolve(SaHolder.getRequest().getHeader("User-Agent"));
        } catch (Exception e) {
            return DEVICE_PC;
        }
    }

    /**
     * 由 User-Agent 判定终端类型。
     *
     * <p>口径与前端 {@code utils/device.ts} 的 isMobileDevice 保持一致：安卓壳标记优先，
     * 其次命中常见手机机型关键字。平板（iPad 等）按电脑端处理——它们屏幕够大，用的是桌面版页面。</p>
     */
    public static String resolve(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) return DEVICE_PC;
        if (userAgent.contains(APP_SHELL_FLAG)) return DEVICE_APP;
        String ua = userAgent.toLowerCase();
        if (ua.contains("ipad")) return DEVICE_PC;
        boolean phone = ua.contains("android")
                || ua.contains("iphone")
                || ua.contains("ipod")
                || ua.contains("windows phone")
                || ua.contains("blackberry")
                || ua.contains("iemobile")
                || ua.contains("opera mini")
                || ua.contains("mobile");
        return phone ? DEVICE_APP : DEVICE_PC;
    }

    /** 该终端的空闲超时（分钟）：手机端放宽，电脑端保持原样。 */
    public static int idleTimeoutMinutes(String device, int pcMinutes, int appMinutes) {
        return DEVICE_APP.equals(device) ? appMinutes : pcMinutes;
    }

    /** 给人看的名字，用在"在线用户"列表里。 */
    public static String label(String device) {
        return DEVICE_APP.equals(device) ? "手机" : "电脑";
    }
}
