package com.group.scm.common.aop;

import cn.dev33.satoken.stp.StpUtil;
import com.alibaba.fastjson2.JSON;
import com.group.scm.common.BizException;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 防重复提交（幂等）切面。
 *
 * <p>Bug #5 修复：拦截 {@link Idempotent} 注解的方法，在时间窗内对「同一用户 + 同一方法 + 同一参数」
 * 的重复请求直接拒绝。系统为单机部署，这里用进程内 {@link ConcurrentHashMap} 做轻量去重，
 * 无需引入 Redis 等分布式组件。</p>
 *
 * <p>键的过期时间随访问惰性清理：每次进入时顺手移除已过期的键，避免长期运行内存增长。</p>
 */
@Slf4j
@Aspect
@Component
public class IdempotencyAspect {

    /** key -> 过期时间戳（毫秒） */
    private final ConcurrentHashMap<String, Long> seen = new ConcurrentHashMap<>();

    @Around("@annotation(idempotent)")
    public Object around(ProceedingJoinPoint pjp, Idempotent idempotent) throws Throwable {
        String key = buildKey(pjp);
        long now = System.currentTimeMillis();
        long expireAt = now + idempotent.intervalSeconds() * 1000L;

        // 惰性清理已过期的键
        seen.entrySet().removeIf(e -> e.getValue() <= now);

        // putIfAbsent 原子判断：已存在且未过期 → 判定为重复提交
        Long existing = seen.putIfAbsent(key, expireAt);
        if (existing != null && existing > now) {
            throw new BizException(4290, idempotent.message());
        }
        // 覆盖为最新过期时间（兼容刚被清理的边界情况）
        seen.put(key, expireAt);
        try {
            return pjp.proceed();
        } finally {
            // 业务执行完即放行，允许用户重新提交（去重只针对「并发/连点」的瞬时重复）
            seen.remove(key, expireAt);
        }
    }

    private String buildKey(ProceedingJoinPoint pjp) {
        String uid;
        try {
            uid = String.valueOf(StpUtil.getLoginIdAsLong());
        } catch (Exception e) {
            uid = "anonymous";
        }
        MethodSignature sig = (MethodSignature) pjp.getSignature();
        String method = sig.getDeclaringType().getName() + "#" + sig.getMethod().getName();
        String args;
        try {
            args = JSON.toJSONString(pjp.getArgs());
        } catch (Exception e) {
            args = String.valueOf(System.identityHashCode(pjp.getArgs()));
        }
        return uid + "|" + method + "|" + args;
    }
}
