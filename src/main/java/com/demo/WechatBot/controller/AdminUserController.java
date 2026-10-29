package com.demo.WechatBot.controller;

import com.demo.WechatBot.service.AdminUserPrincipal;
import com.demo.WechatBot.service.AdminUserService;
import com.demo.WechatBot.service.AdminUserStore;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/users")
public class AdminUserController {

    public record CreateUserRequest(String username, String password, String role) {
    }

    public record EnabledRequest(boolean enabled) {
    }

    public record PasswordRequest(String password) {
    }

    public record UserView(String id, String username, String role, boolean enabled,
                           String createdAt, String lastLoginAt) {
        static UserView from(AdminUserStore.AdminUser user) {
            return new UserView(user.id(), user.username(), user.role(), user.enabled(),
                    user.createdAt(), user.lastLoginAt());
        }
    }

    private final AdminUserService userService;

    public AdminUserController(AdminUserService userService) {
        this.userService = userService;
    }

    @GetMapping
    public List<UserView> listUsers() {
        return userService.listUsers().stream().map(UserView::from).toList();
    }

    @PostMapping
    public ResponseEntity<UserView> createUser(@RequestBody CreateUserRequest request) {
        AdminUserStore.AdminUser user = userService.createUser(
                request.username(), request.password(), request.role());
        return ResponseEntity.status(HttpStatus.CREATED).body(UserView.from(user));
    }

    @PostMapping("/{userId}/enabled")
    public ResponseEntity<Void> setEnabled(@PathVariable String userId,
                                           @RequestBody EnabledRequest request,
                                           @AuthenticationPrincipal AdminUserPrincipal principal) {
        userService.setEnabled(principal.getId(), userId, request.enabled());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{userId}/password")
    public ResponseEntity<Void> resetPassword(@PathVariable String userId,
                                              @RequestBody PasswordRequest request) {
        userService.resetPassword(userId, request.password());
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleBadRequest(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(Map.of("error", exception.getMessage()));
    }
}
