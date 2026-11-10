package com.group.scm.common.aop;

import java.lang.annotation.*;

/**
 * 防重复提交（幂等）注解。
 *
 * <p>Bug #5 修复：审批模式切换、月末结账等关键写操作，若前端按钮未禁用或用户连点，
 * 可能在极短时间内被重复提交，导致双写、数据不一致、审计日志冗余。</p>
 *
 * <p>用法：</p>
 * <pre>
 * &#64;Idempotent(intervalSeconds = 3)
 * public Result&lt;?&gt; updateApprovalMode(...) { ... }
 * </pre>
 *
 * <p>在 {@code intervalSeconds} 时间窗内，同一用户对同一方法提交完全相同的参数，
 * 第二次及以后的请求会被 {@link IdempotencyAspect} 直接拒绝（返回业务错误，不进入业务逻辑）。</p>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Idempotent {

    /** 去重时间窗（秒），默认 3 秒 */
    int intervalSeconds() default 3;

    /** 命中重复提交时的提示信息 */
    String message() default "操作正在处理中，请勿重复提交";
}
