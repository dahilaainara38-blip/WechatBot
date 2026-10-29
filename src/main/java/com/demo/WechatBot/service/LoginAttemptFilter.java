package com.demo.WechatBot.service;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public class LoginAttemptFilter extends OncePerRequestFilter {

    private final LoginAttemptService attemptService;
    private final SecurityResponseWriter responseWriter;

    public LoginAttemptFilter(LoginAttemptService attemptService, SecurityResponseWriter responseWriter) {
        this.attemptService = attemptService;
        this.responseWriter = responseWriter;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !HttpMethod.POST.matches(request.getMethod())
                || !"/api/auth/login".equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String username = request.getParameter("username");
        if (attemptService.isLocked(username, request.getRemoteAddr())) {
            responseWriter.writeError(response, 429, "登录尝试过于频繁，请稍后再试");
            return;
        }
        filterChain.doFilter(request, response);
    }
}
