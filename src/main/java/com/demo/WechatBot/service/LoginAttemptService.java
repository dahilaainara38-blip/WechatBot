package com.demo.WechatBot.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 单实例登录限流。部署为多实例时应迁移到 Redis 等共享存储。
 */
@Service
public class LoginAttemptService {

    private record Attempt(int failures, Instant lockedUntil) {
    }

    private final Map<String, Attempt> attempts = new ConcurrentHashMap<>();
    private final int maxFailures;
    private final Duration lockDuration;

    public LoginAttemptService(
            @Value("${app.security.login-max-failures:5}") int maxFailures,
            @Value("${app.security.login-lock-seconds:900}") long lockSeconds) {
        this.maxFailures = maxFailures;
        this.lockDuration = Duration.ofSeconds(lockSeconds);
    }

    public boolean isLocked(String username, String ipAddress) {
        String key = key(username, ipAddress);
        Attempt attempt = attempts.get(key);
        if (attempt == null || attempt.lockedUntil() == null) {
            return false;
        }
        if (Instant.now().isBefore(attempt.lockedUntil())) {
            return true;
        }
        attempts.remove(key);
        return false;
    }

    public boolean recordFailure(String username, String ipAddress) {
        String key = key(username, ipAddress);
        Attempt updated = attempts.compute(key, (k, current) -> {
            int failures = current == null ? 1 : current.failures() + 1;
            if (failures >= maxFailures) {
                return new Attempt(0, Instant.now().plus(lockDuration));
            }
            return new Attempt(failures, null);
        });
        return updated.lockedUntil() != null;
    }

    public void recordSuccess(String username, String ipAddress) {
        attempts.remove(key(username, ipAddress));
    }

    private String key(String username, String ipAddress) {
        String normalized = username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
        return normalized + "|" + (ipAddress == null ? "unknown" : ipAddress);
    }
}
