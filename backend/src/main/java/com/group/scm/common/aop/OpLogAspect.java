package com.group.scm.common.aop;

import cn.dev33.satoken.stp.StpUtil;
import com.alibaba.fastjson2.JSON;
import com.group.scm.common.IpUtil;
import com.group.scm.modules.system.entity.SysLog;
import com.group.scm.modules.system.entity.SysUser;
import com.group.scm.modules.system.mapper.SysLogMapper;
import com.group.scm.modules.system.mapper.SysUserMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.LocalDateTime;
import java.util.regex.Pattern;

/**
 * 操作日志 AOP 切面。
 *
 * <p>拦截 {@link OpLog} 注解的方法 → 记录调用人 / 接口 / 入参 / 耗时 / 成功状态 / 异常信息到 sys_log 表。</p>
 *
 * <p><b>为什么在方法执行前就取操作人</b>(2026-09-08 修):
 * 有些接口会在自己内部把当前会话注销掉(如管理员编辑/重置自己的账号后强制重登),
 * 等到 finally 里再去 {@code StpUtil.getLoginIdAsLong()} 就已经取不到人了,
 * 日志会写成"操作人空白"——曾出现的无主日志全部是这个原因。
 * 所以操作人必须在 {@code proceed()} 之前先抓在手里。</p>
 *
 * <p><b>为什么落库前要脱敏</b>(2026-09-08 修):
 * {@link OpLog#recordBody()} 默认 true,重置密码这类接口一旦忘记关,
 * 明文新密码就会原样落到 {@code sys_log.request_body},还会跟着全库备份传到异地。
 * 逐个接口"记得关"是人肉纪律,靠不住,所以这里统一做一道兜底脱敏。</p>
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class OpLogAspect {

    private final SysLogMapper logMapper;
    private final SysUserMapper userMapper;

    /** 敏感字段:JSON 里这些 key 的值一律替换成 ***,新增敏感字段往这里加。 */
    private static final Pattern SENSITIVE = Pattern.compile(
            "(?i)(\"(?:password|passwd|pwd|newPassword|oldPassword|confirmPassword|token|secret|secretKey|accessKey|credential)\"\\s*:\\s*)\"(?:\\\\.|[^\"\\\\])*\"");

    @Around("@annotation(opLog)")
    public Object around(ProceedingJoinPoint pjp, OpLog opLog) throws Throwable {
        long t0 = System.currentTimeMillis();
        // 操作人必须在业务方法执行前取:方法内部可能把当前会话注销掉(见类注释)
        Long uid = currentUid();
        Object result = null;
        Throwable error = null;
        try {
            result = pjp.proceed();
            return result;
        } catch (Throwable t) {
            error = t;
            throw t;
        } finally {
            try {
                writeLog(pjp, opLog, uid, result, error, System.currentTimeMillis() - t0);
            } catch (Exception e) {
                log.warn("写操作日志失败", e);
            }
        }
    }

    private Long currentUid() {
        try {
            return StpUtil.getLoginIdAsLong();
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * 落库。注意:这里是同步执行的——曾经挂着 @Async,但切面是 this.writeLog(...) 自调用,
     * Spring 代理拦不到,那个注解从来没生效过。已去掉,免得后人以为它在异步。
     */
    public void writeLog(ProceedingJoinPoint pjp, OpLog opLog, Long uid,
                         Object result, Throwable error, long durationMs) {
        SysLog logEntity = new SysLog();
        logEntity.setModule(opLog.module());
        logEntity.setAction(opLog.action());
        logEntity.setSuccess(error == null ? 1 : 0);
        if (error != null) logEntity.setErrorMsg(safeTrim(com.group.scm.common.SafeErrorMessage.of(error), 500));
        logEntity.setDurationMs((int) Math.min(durationMs, Integer.MAX_VALUE));
        logEntity.setCreatedAt(LocalDateTime.now());

        // 用户信息(uid 已在方法执行前抓好)
        if (uid != null) {
            logEntity.setUserId(uid);
            try {
                SysUser u = userMapper.selectById(uid);
                if (u != null) logEntity.setUsername(u.getUsername());
            } catch (Exception ignored) {}
        }

        // 请求信息
        try {
            ServletRequestAttributes attr = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attr != null) {
                HttpServletRequest req = attr.getRequest();
                logEntity.setIp(IpUtil.clientIp(req));
                logEntity.setRequestUri(req.getRequestURI());
            }
        } catch (Exception ignored) {}

        // 入参
        if (opLog.recordBody()) {
            try {
                Object[] args = pjp.getArgs();
                if (args != null && args.length > 0) {
                    logEntity.setRequestBody(safeTrim(mask(JSON.toJSONString(args)), 4000));
                }
            } catch (Exception ignored) {}
        }

        // 出参（成功才记，失败的话已经记了 error）
        if (result != null && error == null) {
            // 文件下载(备份、附件)只记大小,绝不把文件内容序列化进日志(安全审查 S06 残留,2026-10-18)
            Object body = result instanceof org.springframework.http.ResponseEntity<?> re ? re.getBody() : result;
            if (body instanceof byte[] bytes) {
                logEntity.setResponse("下载文件 " + bytes.length + " 字节");
            } else if (body instanceof org.springframework.core.io.Resource
                    || body instanceof org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody) {
                logEntity.setResponse("下载文件(流式)");
            } else {
                try { logEntity.setResponse(safeTrim(mask(JSON.toJSONString(result)), 4000)); } catch (Exception ignored) {}
            }
        }

        logMapper.insert(logEntity);
    }

    /** 把 JSON 里的密码/令牌类字段值替换成 ***,不改变 JSON 结构。 */
    static String mask(String json) {
        if (json == null || json.isEmpty()) return json;
        return SENSITIVE.matcher(json).replaceAll("$1\"***\"");
    }

    private String safeTrim(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
