package com.demo.WechatBot;

import com.demo.WechatBot.service.BotAccountStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BotAccountStoreMigrationTest {

    @TempDir
    Path tempDir;

    private SingleConnectionDataSource dataSource;

    @AfterEach
    void tearDown() {
        if (dataSource != null) {
            dataSource.destroy();
        }
    }

    @Test
    void legacyBotAccountsGainOwnerColumnAndCanBeAssigned() {
        dataSource = new SingleConnectionDataSource(
                "jdbc:sqlite:" + tempDir.resolve("legacy.db"), true);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                CREATE TABLE bot_accounts (
                    bot_id          TEXT PRIMARY KEY,
                    user_id         TEXT,
                    bot_token       TEXT NOT NULL,
                    base_url        TEXT,
                    updates_cursor  TEXT,
                    status          TEXT NOT NULL DEFAULT 'ONLINE',
                    created_at      TEXT NOT NULL DEFAULT (datetime('now', 'localtime')),
                    updated_at      TEXT NOT NULL DEFAULT (datetime('now', 'localtime'))
                )
                """);
        jdbc.update("""
                INSERT INTO bot_accounts (bot_id, user_id, bot_token, base_url, status)
                VALUES ('legacy-bot', 'legacy-user', 'legacy-token', 'https://ilinkai.weixin.qq.com', 'ONLINE')
                """);

        BotAccountStore store = new BotAccountStore(jdbc);
        store.initSchema();
        assertEquals(1, store.assignUnownedBots("owner-a"));

        List<Map<String, Object>> columns = jdbc.queryForList("PRAGMA table_info(bot_accounts)");
        assertTrue(columns.stream().anyMatch(column -> "owner_id".equals(column.get("name"))));
        assertEquals("owner-a", store.findByBotId("legacy-bot").orElseThrow().ownerId());
    }
}
