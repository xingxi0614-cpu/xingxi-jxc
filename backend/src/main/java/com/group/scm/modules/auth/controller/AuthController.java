package com.group.scm.modules.auth.controller;

import com.group.scm.common.IpUtil;
import com.group.scm.common.Result;
import com.group.scm.common.service.OrgScopeService;
import com.group.scm.modules.auth.dto.ChangePasswordDTO;
import com.group.scm.modules.auth.dto.LoginDTO;
import com.group.scm.modules.auth.dto.LoginVO;
import com.group.scm.modules.auth.dto.ProfileUpdateDTO;
import com.group.scm.modules.auth.dto.UserInfoVO;
import com.group.scm.modules.auth.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

@Tag(name = "认证")
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final OrgScopeService orgScopeService;

    /** Cookie 中存储 token 的键名，与 sa-token.token-name 一致 */
    private static final String TOKEN_COOKIE = "Authorization";
    private static final int COOKIE_MAX_AGE = 28800; // 8 小时，与 sa-token.timeout 一致

    /** 是否强制 HTTPS Cookie（生产环境通过 Nginx 终止 TLS 时为 false） */
    @Value("${scm.cookie.secure:false}")
    private boolean cookieSecure;

    @Operation(summary = "登录")
    @PostMapping("/login")
    public Result<LoginVO> login(@Valid @RequestBody LoginDTO dto,
                                  HttpServletRequest req,
                                  HttpServletResponse resp) {
        LoginVO vo = authService.login(dto, IpUtil.clientIp(req));
        setTokenCookie(resp, vo.getToken());
        return Result.ok(vo);
    }

    @Operation(summary = "登出")
    @PostMapping("/logout")
    public Result<?> logout(HttpServletResponse resp) {
        authService.logout();
        clearTokenCookie(resp);
        return Result.ok();
    }

    // ... rest unchanged

    private void setTokenCookie(HttpServletResponse resp, String token) {
        Cookie cookie = new Cookie(TOKEN_COOKIE, token);
        cookie.setHttpOnly(true);
        cookie.setSecure(cookieSecure);
        cookie.setPath("/");
        cookie.setMaxAge(COOKIE_MAX_AGE);
        cookie.setAttribute("SameSite", "Lax");
        resp.addCookie(cookie);
    }

    private void clearTokenCookie(HttpServletResponse resp) {
        Cookie cookie = new Cookie(TOKEN_COOKIE, "");
        cookie.setHttpOnly(true);
        cookie.setSecure(cookieSecure);
        cookie.setPath("/");
        cookie.setMaxAge(0);
        cookie.setAttribute("SameSite", "Lax");
        resp.addCookie(cookie);
    }

    @Operation(summary = "当前用户信息")
    @GetMapping("/me")
    public Result<UserInfoVO> me() {
        return Result.ok(authService.me());
    }

    @Operation(summary = "顶栏查看公司：可选公司 + 组织所属公司名(打印抬头用)，范围限于本人权限内")
    @GetMapping("/view-scope")
    public Result<java.util.Map<String, Object>> viewScope() {
        return Result.ok(orgScopeService.viewScope());
    }

    @Operation(summary = "更新当前用户个人信息")
    @PutMapping("/me/profile")
    public Result<UserInfoVO> updateProfile(@RequestBody ProfileUpdateDTO dto) {
        return Result.ok(authService.updateProfile(dto));
    }

    @Operation(summary = "当前用户修改密码")
    @PostMapping("/me/change-password")
    public Result<?> changePassword(@RequestBody ChangePasswordDTO dto) {
        authService.changePassword(dto);
        return Result.ok();
    }

    @Operation(summary = "健康检查")
    @GetMapping("/ping")
    public Result<String> ping() {
        return Result.ok("pong");
    }
}
