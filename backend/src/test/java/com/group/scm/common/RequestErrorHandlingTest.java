package com.group.scm.common;

import cn.dev33.satoken.spring.SaBeanInject;
import cn.dev33.satoken.spring.SaBeanRegister;
import cn.dev33.satoken.spring.SaTokenContextRegister;
import com.group.scm.common.service.OrgScopeService;
import com.group.scm.modules.auth.controller.AuthController;
import com.group.scm.modules.auth.service.AuthService;
import com.group.scm.config.SaTokenConfig;
import com.group.scm.modules.notif.controller.NotifController;
import com.group.scm.modules.notif.service.NotifService;
import com.group.scm.modules.system.controller.SysHealthController;
import com.group.scm.modules.system.service.OnlineSessionInterceptor;
import com.group.scm.modules.system.service.OnlineSessionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.beans.ConversionNotSupportedException;
import org.springframework.core.MethodParameter;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.web.bind.MissingPathVariableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.method.annotation.ExceptionHandlerMethodResolver;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.NoHandlerFoundException;

import javax.sql.DataSource;
import java.lang.reflect.Method;
import java.sql.Connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 不合规的请求回对应的 HTTP 状态码、不记 ERROR(纯 Web 层,不需要数据库,任何时候都会跑)。
 * 地址不存在 404;请求方式不对 405;内容格式不对 415;参数缺失/格式错 400;上传超限 413。
 *
 * <p>由来:关掉接口文档后 /v3/api-docs 仍在登录白名单里,
 * 探测一下就落进兜底处理器——记一条带整段堆栈的 ERROR、回 200+5000"系统异常"。
 * 扫描器乱试路径会把日志刷满假 ERROR,淹没真故障。</p>
 *
 * <p>同时守住老行为:要登录的真接口未登录仍回 4001,白名单里的真接口照常可用。
 * 登录拦截器、白名单、全局异常处理器都是生产同一份(SaTokenConfig / GlobalExceptionHandler)。</p>
 */
@WebMvcTest
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("不合规请求:正确状态码、不记 ERROR")
class RequestErrorHandlingTest {

    /**
     * 只装 Web 层要用的几个真组件。不用主程序配置:它的 @MapperScan 会把所有 Mapper 拉进来要数据库。
     */
    @Configuration
    @ImportAutoConfiguration({SaBeanRegister.class, SaBeanInject.class, SaTokenContextRegister.class})
    @Import({GlobalExceptionHandler.class, SaTokenConfig.class, OnlineSessionInterceptor.class,
            NotifController.class, SysHealthController.class, AuthController.class})
    static class WebOnly {
    }

    @Autowired private MockMvc mvc;

    @MockitoBean private OnlineSessionService onlineSessionService;
    @MockitoBean private NotifService notifService;
    @MockitoBean private OrgScopeService orgScopeService;
    @MockitoBean private DataSource dataSource;
    @MockitoBean private AuthService authService;

    @Test
    @DisplayName("白名单前缀下不存在的地址(/lr-public/...) → HTTP 404,不记 ERROR,不回显路径")
    void unknownWhitelistedPath_is404_withoutErrorLog(CapturedOutput output) throws Exception {
        String body = mvc.perform(get("/lr-public/no-such-thing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(4004))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("lr-public").doesNotContain("no-such-thing");
        assertThat(output).doesNotContain("Unhandled exception").doesNotContain(" ERROR ");
    }

    @Test
    @DisplayName("接口文档地址已移出白名单:未登录探测 /v3/api-docs、/doc.html、/swagger-ui、/webjars → 4001")
    void docsPaths_notWhitelistedAnymore() throws Exception {
        for (String p : new String[]{"/v3/api-docs", "/v3/api-docs/swagger-config", "/doc.html",
                "/swagger-ui/index.html", "/webjars/x.js"}) {
            mvc.perform(get(p))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(4001));
        }
    }

    @Test
    @DisplayName("另一个白名单静态路径(/favicon.ico) → HTTP 404")
    void unknownStaticPath_is404(CapturedOutput output) throws Exception {
        mvc.perform(get("/favicon.ico"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(4004));
        assertThat(output).doesNotContain("Unhandled exception");
    }

    @Test
    @DisplayName("要登录的真接口未登录 → 仍是 HTTP 200 + code 4001(老行为不变)")
    void realEndpoint_withoutLogin_is4001() throws Exception {
        mvc.perform(get("/notif/my"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(4001));
    }

    @Test
    @DisplayName("要登录的范围里乱试一个不存在的地址,未登录 → 先被登录拦截挡下回 4001,不暴露地址存不存在")
    void unknownProtectedPath_withoutLogin_is4001() throws Exception {
        mvc.perform(get("/no-such-module/list"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(4001));
    }

    @Test
    @DisplayName("白名单里的真接口(/system/health) → 不登录照常可用")
    void whitelistedRealEndpoint_stillWorks() throws Exception {
        Connection conn = mock(Connection.class);
        given(conn.isValid(anyInt())).willReturn(true);
        given(dataSource.getConnection()).willReturn(conn);

        mvc.perform(get("/system/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    @DisplayName("NoHandlerFoundException(关掉静态资源映射时会抛这个)同样回 404 + 4004")
    void noHandlerFound_isAlso404() throws Exception {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        Result<?> r = handler.handleNotFound(
                new NoHandlerFoundException("GET", "/secret/internal", new HttpHeaders()));

        assertThat(r.getCode()).isEqualTo(4004);
        assertThat(r.getMsg()).doesNotContain("/secret/internal");
        // @ResponseStatus 决定 HTTP 状态码,确认注解在
        assertThat(GlobalExceptionHandler.class
                .getMethod("handleNotFound", Exception.class)
                .getAnnotation(org.springframework.web.bind.annotation.ResponseStatus.class)
                .value()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ───────── 请求方式 / 内容格式 / 参数(2026-10-18 第二批) ─────────

    @Test
    @DisplayName("GET 打只收 POST 的免登录接口(/auth/login) → HTTP 405 + 4050,不记 ERROR")
    void wrongMethod_is405_withoutErrorLog(CapturedOutput output) throws Exception {
        mvc.perform(get("/auth/login"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value(4050));
        assertThat(output).doesNotContain("Unhandled exception").doesNotContain(" ERROR ");
    }

    @Test
    @DisplayName("请求方式不对发生在登录拦截之前:未登录 GET 打要登录的 POST 接口也回 405")
    void wrongMethod_onProtectedEndpoint_is405() throws Exception {
        mvc.perform(get("/notif/read-all"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value(4050));
    }

    @Test
    @DisplayName("该传 JSON 却传纯文本 → HTTP 415 + 4150")
    void wrongContentType_is415(CapturedOutput output) throws Exception {
        mvc.perform(post("/auth/login").contentType(MediaType.TEXT_PLAIN).content("x"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value(4150));
        assertThat(output).doesNotContain("Unhandled exception");
    }

    @Test
    @DisplayName("JSON 写坏了 → HTTP 400 + 4000,回包不带 Jackson/类名等内部信息")
    void brokenJson_is400_withoutInternals(CapturedOutput output) throws Exception {
        String body = mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"username\":"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(4000))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("JSON").doesNotContain("com.group").doesNotContain("Jackson");
        assertThat(output).doesNotContain("Unhandled exception").doesNotContain(" ERROR ");
    }

    /**
     * 每种异常落到哪个处理方法、回什么 HTTP 状态——直接问 Spring 的解析器,和运行时同一套规则。
     * 重点是后半段:真故障(上传临时目录坏了、类型转换器没配、路径变量写错)必须仍落兜底记 ERROR。
     */
    @Test
    @DisplayName("异常 → 处理方法映射:客户端错误各归各位,真故障仍落兜底")
    void exceptionMapping() throws Exception {
        ExceptionHandlerMethodResolver resolver = new ExceptionHandlerMethodResolver(GlobalExceptionHandler.class);
        MethodParameter anyParam = new MethodParameter(Object.class.getMethod("equals", Object.class), 0);

        assertMapped(resolver, new HttpRequestMethodNotSupportedException("GET"), "handleMethodNotAllowed", HttpStatus.METHOD_NOT_ALLOWED);
        assertMapped(resolver, new HttpMediaTypeNotSupportedException("text/plain"), "handleMediaType", HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertMapped(resolver, new MaxUploadSizeExceededException(20L << 20), "handleTooLarge", HttpStatus.PAYLOAD_TOO_LARGE);
        assertMapped(resolver, new MissingServletRequestParameterException("id", "Long"), "handleBadRequest", HttpStatus.BAD_REQUEST);
        assertMapped(resolver, new MissingServletRequestPartException("file"), "handleBadRequest", HttpStatus.BAD_REQUEST);
        assertMapped(resolver, new MethodArgumentTypeMismatchException("abc", Long.class, "id", anyParam, null),
                "handleBadRequest", HttpStatus.BAD_REQUEST);
        assertMapped(resolver, new HttpMessageNotReadableException("bad", new MockHttpInputMessage(new byte[0])),
                "handleBadRequest", HttpStatus.BAD_REQUEST);

        // 真故障:不许被压成 WARN
        assertThat(resolver.resolveMethod(new MultipartException("The temporary upload location is not valid")).getName())
                .isEqualTo("handleAny");
        assertThat(resolver.resolveMethod(new ConversionNotSupportedException("x", Long.class, null)).getName())
                .isEqualTo("handleAny");
        assertThat(resolver.resolveMethod(new MissingPathVariableException("id", anyParam)).getName())
                .isEqualTo("handleAny");
    }

    private static void assertMapped(ExceptionHandlerMethodResolver resolver, Exception e,
                                     String method, HttpStatus status) {
        Method m = resolver.resolveMethod(e);
        assertThat(m).as(e.getClass().getSimpleName()).isNotNull();
        assertThat(m.getName()).as(e.getClass().getSimpleName()).isEqualTo(method);
        assertThat(m.getAnnotation(ResponseStatus.class).value()).as(e.getClass().getSimpleName()).isEqualTo(status);
    }
}
