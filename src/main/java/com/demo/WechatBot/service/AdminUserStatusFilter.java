package com.demo.WechatBot.service;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 禁用账号或重置密码后，使已有 Session 在下一次请求时立即失效。
 */
@Component
public class AdminUserStatusFilter extends OncePerRequestFilter {

    private final AdminUserStore userStore;
    private final SecurityResponseWriter responseWriter;

    public AdminUserStatusFilter(AdminUserStore userStore, SecurityResponseWriter responseWriter) {
        this.userStore = userStore;
        this.responseWriter = responseWriter;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return "/api/auth/login".equals(path) || "/api/auth/csrf".equals(path);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof AdminUserPrincipal principal) {
            AdminUserStore.AdminUser current = userStore.findById(principal.getId()).orElse(null);
            boolean invalid = current == null || !current.enabled()
                    || !current.passwordHash().equals(principal.getPassword());
            if (invalid) {
                HttpSession session = request.getSession(false);
                if (session != null) {
                    session.invalidate();
                }
                SecurityContextHolder.clearContext();
                if (request.getRequestURI().startsWith("/api/")) {
                    responseWriter.writeError(response, 401, "登录状态已失效，请重新登录");
                } else {
                    response.sendRedirect("/login.html");
                }
                return;
            }
        }
        filterChain.doFilter(request, response);
    }
}
