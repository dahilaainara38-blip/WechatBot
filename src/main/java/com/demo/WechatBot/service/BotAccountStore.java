package com.demo.WechatBot.service;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 机器人账号存储层。
 * <p>
 * 每个扫码登录的微信机器人一行，持久化登录态（botToken 等），
 * 使服务重启后可以通过 ResumeContext 免扫码恢复上线。
 * </p>
 */
@Component
public class BotAccountStore {

    private static final Logger log = LoggerFactory.getLogger(BotAccountStore.class);

    public static final String STATUS_ONLINE = "ONLINE";
    public static final String STATUS_OFFLINE = "OFFLINE";

    public record BotAccount(String botId, String ownerId, String userId, String botToken, String baseUrl,
                             String updatesCursor, String status, String updatedAt) {
    }

    private static final String COLUMNS =
            "bot_id, owner_id, user_id, bot_token, base_url, updates_cursor, status, updated_at";

    private final JdbcTemplate jdbc;

    public BotAccountStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    public void initSchema() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS bot_accounts (
                    bot_id          TEXT PRIMARY KEY,
                    owner_id        TEXT,
                    user_id         TEXT,
                    bot_token       TEXT NOT NULL,
                    base_url        TEXT,
                    updates_cursor  TEXT,
                    status          TEXT NOT NULL DEFAULT 'ONLINE',
                    created_at      TEXT NOT NULL DEFAULT (datetime('now', 'localtime')),
                    updated_at      TEXT NOT NULL DEFAULT (datetime('now', 'localtime'))
                )
                """);
        migrateOwnerColumn();
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_bot_accounts_owner ON bot_accounts(owner_id)");
        log.info("Bot account store initialized");
    }

    /** 兼容已有 SQLite 文件。 */
    private void migrateOwnerColumn() {
        List<Map<String, Object>> columns = jdbc.queryForList("PRAGMA table_info(bot_accounts)");
        boolean hasOwner = columns.stream()
                .anyMatch(column -> "owner_id".equals(column.get("name")));
        if (!hasOwner) {
            jdbc.execute("ALTER TABLE bot_accounts ADD COLUMN owner_id TEXT");
            log.info("已为 bot_accounts 增加 owner_id 字段");
        }
    }

    /**
     * 登录成功后保存或更新机器人账号（UPSERT），状态置为 ONLINE
     */
    public void upsertOnline(String ownerId, String botId, String userId, String botToken, String baseUrl) {
        if (ownerId == null || ownerId.isBlank()) {
            throw new IllegalArgumentException("机器人必须归属一个管理账号");
        }
        int affected = jdbc.update(
                "UPDATE bot_accounts SET user_id = ?, bot_token = ?, base_url = ?, status = ?, " +
                        "updated_at = datetime('now', 'localtime') WHERE bot_id = ? AND owner_id = ?",
                userId, botToken, baseUrl, STATUS_ONLINE, botId, ownerId
        );
        if (affected == 0) {
            jdbc.update(
                    "INSERT INTO bot_accounts (bot_id, owner_id, user_id, bot_token, base_url, status) " +
                            "VALUES (?, ?, ?, ?, ?, ?)",
                    botId, ownerId, userId, botToken, baseUrl, STATUS_ONLINE
            );
        }
        log.info("机器人账号已保存 [botId={}, ownerId={}, userId={}]", botId, ownerId, userId);
    }

    /**
     * 更新消息拉取游标（停机时调用，避免重启后重复拉取旧消息）
     */
    public void updateCursor(String botId, String cursor) {
        jdbc.update(
                "UPDATE bot_accounts SET updates_cursor = ?, updated_at = datetime('now', 'localtime') WHERE bot_id = ?",
                cursor, botId
        );
    }

    /**
     * 标记机器人为离线（心跳连续失败或恢复失败时调用）
     */
    public void markOffline(String botId) {
        jdbc.update(
                "UPDATE bot_accounts SET status = ?, updated_at = datetime('now', 'localtime') WHERE bot_id = ?",
                STATUS_OFFLINE, botId
        );
        log.info("机器人已标记离线 [botId={}]", botId);
    }

    /**
     * 删除机器人账号（下线的同时清除登录态）
     */
    public boolean deleteOwned(String botId, String ownerId) {
        int affected = jdbc.update(
                "DELETE FROM bot_accounts WHERE bot_id = ? AND owner_id = ?", botId, ownerId);
        if (affected > 0) {
            log.info("机器人账号已删除 [botId={}, ownerId={}]", botId, ownerId);
        }
        return affected > 0;
    }

    /**
     * 查询所有机器人账号，仅供系统恢复等内部流程使用。
     */
    public List<BotAccount> findAll() {
        return jdbc.query("SELECT " + COLUMNS + " FROM bot_accounts", this::mapAccount);
    }

    public List<BotAccount> findAllByOwner(String ownerId) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM bot_accounts WHERE owner_id = ? ORDER BY created_at, bot_id",
                this::mapAccount, ownerId);
    }

    public Optional<BotAccount> findByBotId(String botId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM bot_accounts WHERE bot_id = ?",
                        this::mapAccount, botId)
                .stream().findFirst();
    }

    public int countByOwner(String ownerId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM bot_accounts WHERE owner_id = ?", Integer.class, ownerId);
        return count != null ? count : 0;
    }

    public int assignUnownedBots(String ownerId) {
        return jdbc.update(
                "UPDATE bot_accounts SET owner_id = ? WHERE owner_id IS NULL OR owner_id = ''", ownerId);
    }

    private BotAccount mapAccount(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new BotAccount(
                rs.getString("bot_id"),
                rs.getString("owner_id"),
                rs.getString("user_id"),
                rs.getString("bot_token"),
                rs.getString("base_url"),
                rs.getString("updates_cursor"),
                rs.getString("status"),
                rs.getString("updated_at"));
    }
}
