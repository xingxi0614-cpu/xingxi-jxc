package com.group.scm.common.aop;

import java.lang.annotation.*;

/**
 * 操作日志注解。
 *
 * <p>用法：</p>
 * <pre>
 * &#64;OpLog(module = "采购申请", action = "创建")
 * public BizPr create(BizPr dto) { ... }
 * </pre>
 *
 * <p>所有写操作 Controller 方法上加此注解，即被 {@link OpLogAspect} 自动记录到 sys_log 表。</p>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface OpLog {
    /** 业务模块名（如 "采购申请"） */
    String module();
    /** 动作（如 "创建" / "审批" / "下推"） */
    String action();
    /** 是否记录请求体（默认 true，敏感接口可关） */
    boolean recordBody() default true;
}
