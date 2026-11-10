package com.group.scm.modules.system.service;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
@RequiredArgsConstructor
public class OnlineSessionInterceptor implements HandlerInterceptor {

    private final OnlineSessionService onlineSessionService;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        onlineSessionService.touchCurrent();
        return true;
    }
}
