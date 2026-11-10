package com.group.scm.common;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;
import cn.dev33.satoken.exception.NotRoleException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.stream.Collectors;

/**
 * 全局异常处理器。
 *
 * <p>所有 Controller 抛出的异常都会被这里捕获，转换为标准 {@link Result} 响应，
 * 避免栈跟踪泄露给前端。</p>
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 业务异常 - 正常错误码 */
    @ExceptionHandler(BizException.class)
    public Result<?> handleBiz(BizException e) {
        log.warn("BizException: {}", e.getMessage());
        return Result.fail(e.getCode(), e.getMessage());
    }

    /** 未登录 / 会话失效：按失效类型给出明确提示，前端据 code=4001 统一跳登录 */
    @ExceptionHandler(NotLoginException.class)
    public Result<?> handleNotLogin(NotLoginException e) {
        String msg = switch (e.getType()) {
            case NotLoginException.BE_REPLACED -> "您的账号已在其他设备登录，当前会话已下线";
            case NotLoginException.KICK_OUT -> "您的账号已被管理员强制下线";
            case NotLoginException.TOKEN_TIMEOUT -> "登录已超时，请重新登录";
            case NotLoginException.TOKEN_FREEZE -> "因长时间未操作，登录已过期，请重新登录";
            default -> "请登录后操作";
        };
        return Result.fail(4001, msg);
    }

    /** 无权限 */
    @ExceptionHandler({NotPermissionException.class, NotRoleException.class})
    public Result<?> handleNotPermission(Exception e) {
        return Result.fail(4003, "无权操作：" + e.getMessage());
    }

    /** 参数校验 (@Valid 触发) */
    @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class})
    public Result<?> handleValidation(Exception e) {
        String msg;
        if (e instanceof MethodArgumentNotValidException ex) {
            msg = ex.getBindingResult().getFieldErrors().stream()
                    .map(f -> f.getField() + ": " + f.getDefaultMessage())
                    .collect(Collectors.joining("; "));
        } else if (e instanceof BindException ex) {
            msg = ex.getFieldErrors().stream()
                    .map(f -> f.getField() + ": " + f.getDefaultMessage())
                    .collect(Collectors.joining("; "));
        } else {
            msg = e.getMessage();
        }
        return Result.fail(4000, "参数错误：" + msg);
    }

    /**
     * 锁等待超时 / 死锁被数据库选为牺牲方:本次操作已整笔回滚,重试即可。
     * 2026-10-18(U06)起所有单据写入都和月末结账互斥,结账进行中的几秒内提交的单据可能撞上。
     */
    @ExceptionHandler(PessimisticLockingFailureException.class)
    public Result<?> handleLockConflict(PessimisticLockingFailureException e) {
        log.warn("锁冲突(已整笔回滚): {}", e.getClass().getSimpleName());
        return Result.fail(4091, "有人正在处理同一批数据（例如月末结账），本次操作未保存，请稍后重试");
    }

    /**
     * 请求的地址不存在:回 HTTP 404,不记 ERROR、不打堆栈。
     * 以前落进下面的兜底,记一条带整段堆栈的 ERROR 并回 200+5000"系统异常"——扫描器乱试路径
     * (如 /v3/api-docs)就会刷出一堆假 ERROR,把真故障淹没。
     * 只在 DEBUG 记,外网探测在 nginx 访问日志里本就有。
     * 回包不带路径,免得把内部路由回显出去。
     * 注意:要登录的地址仍先被登录拦截器挡下回 4001,只有已登录或白名单里的地址才会走到这里。
     */
    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Result<?> handleNotFound(Exception e) {
        log.debug("404: {}", e.getMessage());
        return Result.fail(4004, "请求的地址不存在");
    }

    /*
     * 下面几类是"请求本身不合规"(客户端的错,不是系统故障),以前全落进兜底:记带堆栈的 ERROR、
     * 回 200+5000"系统异常"。扫描器拿 GET 去打只收 POST 的 /auth/login 就是一条假 ERROR;
     * 真用户传 20~50MB 的附件(nginx 放行、Spring 上限 20MB)看到的也是莫名其妙的"系统异常"。
     * 改为回对应的 HTTP 状态码 + 人话,记 WARN 不打堆栈——前端漏传参数这类真 bug 仍能在 WARN 里查到。
     * 回包不带异常原文(里面有 Java 类型名、参数类型等内部信息),原文只进服务器日志。
     * 注意:请求方式不对(405)发生在登录拦截之前,所以未登录也会回 405 而不是 4001。
     */

    /** 请求方式不对,如 GET 打只收 POST 的接口 */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    @ResponseStatus(HttpStatus.METHOD_NOT_ALLOWED)
    public Result<?> handleMethodNotAllowed(HttpRequestMethodNotSupportedException e, HttpServletRequest req) {
        logBadRequest(e, req);
        return Result.fail(4050, "请求方式不正确");
    }

    /** 内容格式不对,如该传 JSON 的接口传了纯文本 */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    @ResponseStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
    public Result<?> handleMediaType(HttpMediaTypeNotSupportedException e, HttpServletRequest req) {
        logBadRequest(e, req);
        return Result.fail(4150, "请求内容格式不正确");
    }

    /** 上传超过 spring.servlet.multipart 的上限(application.yml) */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    @ResponseStatus(HttpStatus.PAYLOAD_TOO_LARGE)
    public Result<?> handleTooLarge(MaxUploadSizeExceededException e, HttpServletRequest req) {
        logBadRequest(e, req);
        return Result.fail(4130, "上传的文件超过大小限制，请压缩或拆分后再传");
    }

    /**
     * 参数缺失 / 参数类型对不上(如 id 传了字母)/ JSON 读不了。
     * ⚠️ 故意只列具体子类,不写父类 MultipartException / TypeMismatchException / ServletRequestBindingException:
     *    父类里混着真故障——上传临时目录不可写(服务器加了只读目录限制后可能出)、类型转换器没配、
     *    路径变量写错——这些必须留在兜底记 ERROR,不能被当成"用户传错了"压成 WARN。
     */
    @ExceptionHandler({MissingServletRequestParameterException.class, MissingServletRequestPartException.class,
            MethodArgumentTypeMismatchException.class, HttpMessageNotReadableException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<?> handleBadRequest(Exception e, HttpServletRequest req) {
        logBadRequest(e, req);
        return Result.fail(4000, "参数错误：请求参数缺失或格式不正确");
    }

    private void logBadRequest(Exception e, HttpServletRequest req) {
        log.warn("请求不合规 {} {} -> {}: {}", req.getMethod(), req.getRequestURI(),
                e.getClass().getSimpleName(), e.getMessage());
    }

    /** 兜底 */
    @ExceptionHandler(Exception.class)
    public Result<?> handleAny(Exception e) {
        log.error("Unhandled exception", e);
        return Result.fail(5000, "系统异常，请联系管理员");
    }
}
