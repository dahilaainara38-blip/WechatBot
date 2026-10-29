package com.demo.WechatBot.service;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class AdminUserService {

    private static final Pattern USERNAME_PATTERN = Pattern.compile("[a-z0-9_.-]{3,32}");

    private final AdminUserStore userStore;
    private final PasswordEncoder passwordEncoder;

    public AdminUserService(AdminUserStore userStore, PasswordEncoder passwordEncoder) {
        this.userStore = userStore;
        this.passwordEncoder = passwordEncoder;
    }

    public List<AdminUserStore.AdminUser> listUsers() {
        return userStore.findAll();
    }

    public AdminUserStore.AdminUser createUser(String username, String password, String role) {
        String normalizedUsername = normalizeUsername(username);
        validatePassword(password);
        if (!AdminUserStore.ROLE_ADMIN.equals(role) && !AdminUserStore.ROLE_USER.equals(role)) {
            throw new IllegalArgumentException("角色无效");
        }
        if (userStore.findByUsername(normalizedUsername).isPresent()) {
            throw new IllegalArgumentException("用户名已存在");
        }

        AdminUserStore.AdminUser user = new AdminUserStore.AdminUser(
                UUID.randomUUID().toString(), normalizedUsername,
                passwordEncoder.encode(password), role, true,
                null, null, null);
        userStore.create(user);
        return userStore.findById(user.id()).orElse(user);
    }

    public void setEnabled(String actorId, String targetUserId, boolean enabled) {
        AdminUserStore.AdminUser target = requireUser(targetUserId);
        if (!enabled && actorId.equals(targetUserId)) {
            throw new IllegalArgumentException("不能禁用当前登录账号");
        }
        if (!enabled && target.enabled()
                && AdminUserStore.ROLE_ADMIN.equals(target.role())
                && userStore.countEnabledAdmins() <= 1) {
            throw new IllegalArgumentException("至少需要保留一个可用的管理员账号");
        }
        if (userStore.setEnabled(targetUserId, enabled) == 0) {
            throw new IllegalArgumentException("账号不存在");
        }
    }

    public void resetPassword(String targetUserId, String newPassword) {
        validatePassword(newPassword);
        requireUser(targetUserId);
        userStore.updatePassword(targetUserId, passwordEncoder.encode(newPassword));
    }

    public String normalizeUsername(String username) {
        String normalized = username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
        if (!USERNAME_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException("用户名需为 3-32 位字母、数字、点、下划线或短横线");
        }
        return normalized;
    }

    private void validatePassword(String password) {
        if (password == null || password.length() < 12) {
            throw new IllegalArgumentException("密码至少需要 12 个字符");
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new IllegalArgumentException("密码的 UTF-8 编码不能超过 72 字节");
        }
    }

    private AdminUserStore.AdminUser requireUser(String userId) {
        return userStore.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("账号不存在"));
    }
}
