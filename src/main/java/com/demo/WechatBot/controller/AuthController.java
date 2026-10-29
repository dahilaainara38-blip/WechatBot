package com.demo.WechatBot.controller;

import com.demo.WechatBot.service.AdminUserPrincipal;
import com.demo.WechatBot.service.AdminUserService;
import com.demo.WechatBot.service.AdminUserStore;
import com.demo.WechatBot.service.LoginAttemptService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AdminUserService userService;
    private final LoginAttemptService attemptService;

    public AuthController(AdminUserService userService, LoginAttemptService attemptService) {
        this.userService = userService;
        this.attemptService = attemptService;
    }

    public record RegisterRequest(String username, String password) {
    }

    @GetMapping("/csrf")
    public Map<String, String> csrf(CsrfToken token) {
        return Map.of(
                "token", token.getToken(),
                "headerName", token.getHeaderName(),
                "parameterName", token.getParameterName());
    }

    /**
     * 自助注册，只能创建普通用户（USER），管理员只能由现有管理员在后台创建。
     * 复用登录限流：同一用户名+IP 连续失败 5 次锁定 15 分钟。
     */
    @PostMapping("/register")
    public ResponseEntity<Map<String, ?>> register(@RequestBody RegisterRequest request,
                                                   HttpServletRequest httpRequest) {
        String username = request.username() == null ? "" : request.username();
        String ip = httpRequest.getRemoteAddr();
        if (attemptService.isLocked(username, ip)) {
            return ResponseEntity.status(429).body(Map.of("error", "注册尝试过于频繁，请稍后再试"));
        }
        try {
            userService.createUser(username, request.password(), AdminUserStore.ROLE_USER);
            attemptService.recordSuccess(username, ip);
            return ResponseEntity.ok(Map.of("ok", true));
        } catch (IllegalArgumentException e) {
            attemptService.recordFailure(username, ip);
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/me")
    public Map<String, String> me(@AuthenticationPrincipal AdminUserPrincipal principal) {
        return Map.of(
                "id", principal.getId(),
                "username", principal.getUsername(),
                "role", principal.getRole());
    }
}
