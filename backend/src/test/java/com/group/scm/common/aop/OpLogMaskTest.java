package com.group.scm.common.aop;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 操作日志脱敏的兜底闸门(纯函数,不需要数据库,任何时候都会跑)。
 *
 * <p>由来:曾发现明文密码躺在 {@code sys_log.request_body} 里
 * (重置密码接口忘了关 {@code recordBody})。光靠"记得关开关"是人肉纪律,
 * 所以在切面里加了统一脱敏 —— 这个测试就是防它日后被人改坏。</p>
 */
class OpLogMaskTest {

    @Test
    @DisplayName("重置密码的入参:明文密码不能出现在日志里")
    void masksResetPasswordBody() {
        String masked = OpLogAspect.mask("[1001,{\"password\":\"Sample#Pwd1\"}]");
        assertFalse(masked.contains("Sample#Pwd1"), "明文密码仍留在日志里:" + masked);
        assertTrue(masked.contains("\"password\":\"***\""), "应替换成 ***,实际:" + masked);
    }

    @Test
    @DisplayName("各种写法的敏感字段都要盖住,大小写也不放过")
    void masksAllSensitiveKeys() {
        String json = "{\"newPassword\":\"a1\",\"oldPassword\":\"b2\",\"pwd\":\"c3\","
                + "\"Token\":\"d4\",\"secretKey\":\"e5\",\"accessKey\":\"f6\"}";
        String masked = OpLogAspect.mask(json);
        for (String plain : new String[]{"a1", "b2", "c3", "d4", "e5", "f6"}) {
            assertFalse(masked.contains("\"" + plain + "\""), "字段值 " + plain + " 没盖住:" + masked);
        }
    }

    @Test
    @DisplayName("密码里带引号/反斜杠也要盖住,不能把后面的字段带跑偏")
    void masksEscapedValue() {
        String masked = OpLogAspect.mask("{\"password\":\"a\\\"b\\\\c\",\"username\":\"zhangsan\"}");
        assertTrue(masked.contains("\"password\":\"***\""), "转义值没盖住:" + masked);
        assertTrue(masked.contains("\"username\":\"zhangsan\""), "把后面的字段带跑偏了:" + masked);
    }

    @Test
    @DisplayName("正常业务字段一个都不能动(审计信息不能丢)")
    void keepsBusinessFields() {
        String json = "[535,{\"name\":\"张三\",\"orgId\":50,\"roleIds\":[361,733],\"username\":\"zhangsan\"}]";
        assertTrue(OpLogAspect.mask(json).equals(json), "业务字段被误改:" + OpLogAspect.mask(json));
    }

    @Test
    @DisplayName("空值不炸")
    void handlesNull() {
        OpLogAspect.mask(null);
        OpLogAspect.mask("");
    }
}
