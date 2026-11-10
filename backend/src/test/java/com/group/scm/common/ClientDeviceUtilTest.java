package com.group.scm.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 终端类型判定的闸门（纯函数，不需要数据库，任何时候都会跑）。
 *
 * <p>由来：加手机 App 时把"一个账号只能一处在线"改成按终端区分，
 * 手机端会话因此能活 2 小时、电脑端仍是 30 分钟。判错终端的后果是两头的：
 * 把电脑认成手机 = 电脑端白白多活 90 分钟（安全口径被放宽）；
 * 把手机认成电脑 = App 每半小时踢一次，等于白做。所以这里逐类钉死。</p>
 */
class ClientDeviceUtilTest {

    @Test
    @DisplayName("安卓 App 壳：认成手机端")
    void appShellIsApp() {
        String ua = "Mozilla/5.0 (Linux; Android 14; SM-S911B) AppleWebKit/537.36 "
                + "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36 ScmApp/1.0.0";
        assertEquals(ClientDeviceUtil.DEVICE_APP, ClientDeviceUtil.resolve(ua));
    }

    @Test
    @DisplayName("手机浏览器直接打开网址：同样按手机端算")
    void phoneBrowserIsApp() {
        String android = "Mozilla/5.0 (Linux; Android 13; PGT110) AppleWebKit/537.36 "
                + "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";
        String iphone = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 "
                + "(KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1";
        assertEquals(ClientDeviceUtil.DEVICE_APP, ClientDeviceUtil.resolve(android));
        assertEquals(ClientDeviceUtil.DEVICE_APP, ClientDeviceUtil.resolve(iphone));
    }

    @Test
    @DisplayName("电脑浏览器：认成电脑端")
    void desktopBrowserIsPc() {
        String mac = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
                + "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";
        String win = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                + "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";
        assertEquals(ClientDeviceUtil.DEVICE_PC, ClientDeviceUtil.resolve(mac));
        assertEquals(ClientDeviceUtil.DEVICE_PC, ClientDeviceUtil.resolve(win));
    }

    @Test
    @DisplayName("平板按电脑端算：屏幕够大，用的是桌面版页面")
    void tabletIsPc() {
        String ipad = "Mozilla/5.0 (iPad; CPU OS 17_5 like Mac OS X) AppleWebKit/605.1.15 "
                + "(KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1";
        // 注意这条 UA 里同时含 "Mobile"，先命中 ipad 才不会被误判成手机
        assertEquals(ClientDeviceUtil.DEVICE_PC, ClientDeviceUtil.resolve(ipad));
    }

    @Test
    @DisplayName("拿不到 UA 时按电脑端算：拿不准就取更严格的那一档")
    void unknownFallsBackToPc() {
        assertEquals(ClientDeviceUtil.DEVICE_PC, ClientDeviceUtil.resolve(null));
        assertEquals(ClientDeviceUtil.DEVICE_PC, ClientDeviceUtil.resolve(""));
        assertEquals(ClientDeviceUtil.DEVICE_PC, ClientDeviceUtil.resolve("   "));
        assertEquals(ClientDeviceUtil.DEVICE_PC, ClientDeviceUtil.resolve("curl/8.7.1"));
    }

    @Test
    @DisplayName("超时分档：手机 120 分钟、电脑 30 分钟，别接反")
    void idleTimeoutPerDevice() {
        assertEquals(120, ClientDeviceUtil.idleTimeoutMinutes(ClientDeviceUtil.DEVICE_APP, 30, 120));
        assertEquals(30, ClientDeviceUtil.idleTimeoutMinutes(ClientDeviceUtil.DEVICE_PC, 30, 120));
        // 未知终端一律走电脑端那档
        assertEquals(30, ClientDeviceUtil.idleTimeoutMinutes(null, 30, 120));
    }

    @Test
    @DisplayName("在线用户列表上显示的是人话")
    void humanReadableLabel() {
        assertEquals("手机", ClientDeviceUtil.label(ClientDeviceUtil.DEVICE_APP));
        assertEquals("电脑", ClientDeviceUtil.label(ClientDeviceUtil.DEVICE_PC));
        assertEquals("电脑", ClientDeviceUtil.label(null));
    }
}
