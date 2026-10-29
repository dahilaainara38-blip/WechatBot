package com.demo.WechatBot.service;

import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 管理后台账号存储。密码字段只保存 BCrypt 哈希。
 */
@Component
public class AdminUserStore {

    public static final String ROLE_ADMIN = "ADMIN";
    public static final String ROLE_USER = "USER";

    public record AdminUser(String id, String username, String passwordHash, String role,
                            boolean enabled, String createdAt, String updatedAt,
                            String lastLoginAt) implements java.io.Serializable {
    }

    private static final String COLUMNS =
            "id, username, password_hash, role, enabled, created_at, updated_at, last_login_at";

    private final JdbcTemplate jdbc;
    private final RowMapper<AdminUser> mapper = (rs, rowNum) -> new AdminUser(
            rs.getString("id"),
            rs.getString("username"),
            rs.getString("password_hash"),
            rs.getString("role"),
            rs.getBoolean("enabled"),
            rs.getString("created_at"),
            rs.getString("updated_at"),
            rs.getString("last_login_at"));

    public AdminUserStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    public void initSchema() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS admin_users (
                    id              TEXT PRIMARY KEY,
                    username        TEXT NOT NULL COLLATE NOCASE UNIQUE,
                    password_hash   TEXT NOT NULL,
                    role            TEXT NOT NULL CHECK (role IN ('ADMIN', 'USER')),
                    enabled         INTEGER NOT NULL DEFAULT 1,
                    created_at      TEXT NOT NULL DEFAULT (datetime('now', 'localtime')),
                    updated_at      TEXT NOT NULL DEFAULT (datetime('now', 'localtime')),
                    last_login_at   TEXT
                )
                """);
    }

    public Optional<AdminUser> findByUsername(String username) {
        return queryOne("SELECT " + COLUMNS + " FROM admin_users WHERE username = ?", username);
    }

    public Optional<AdminUser> findById(String id) {
        return queryOne("SELECT " + COLUMNS + " FROM admin_users WHERE id = ?", id);
    }

    public Optional<AdminUser> findAnyAdmin() {
        return queryOne("SELECT " + COLUMNS +
                " FROM admin_users WHERE role = 'ADMIN' ORDER BY created_at, id LIMIT 1");
    }

    public List<AdminUser> findAll() {
        return jdbc.query("SELECT " + COLUMNS + " FROM admin_users ORDER BY created_at, username", mapper);
    }

    public void create(AdminUser user) {
        jdbc.update("""
                        INSERT INTO admin_users (id, username, password_hash, role, enabled)
                        VALUES (?, ?, ?, ?, ?)
                        """,
                user.id(), user.username(), user.passwordHash(), user.role(), user.enabled() ? 1 : 0);
    }

    public int setEnabled(String id, boolean enabled) {
        return jdbc.update("""
                UPDATE admin_users SET enabled = ?, updated_at = datetime('now', 'localtime') WHERE id = ?
                """, enabled ? 1 : 0, id);
    }

    public int updatePassword(String id, String passwordHash) {
        return jdbc.update("""
                UPDATE admin_users SET password_hash = ?, updated_at = datetime('now', 'localtime') WHERE id = ?
                """, passwordHash, id);
    }

    public void touchLogin(String id) {
        jdbc.update("UPDATE admin_users SET last_login_at = datetime('now', 'localtime') WHERE id = ?", id);
    }

    public int countEnabledAdmins() {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM admin_users WHERE role = 'ADMIN' AND enabled = 1", Integer.class);
        return count != null ? count : 0;
    }

    private Optional<AdminUser> queryOne(String sql, Object... args) {
        return jdbc.query(sql, mapper, args).stream().findFirst();
    }
}
