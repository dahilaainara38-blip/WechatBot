package com.demo.WechatBot;

import com.demo.WechatBot.service.ConversationContextStore;
import com.github.wechat.ilink.sdk.core.context.ConversationContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConversationContextStoreTest {

    @TempDir
    Path tempDir;

    private SingleConnectionDataSource dataSource;

    @AfterEach
    void tearDown() {
        if (dataSource != null) dataSource.destroy();
    }

    @Test
    void incomingContextCanBeRestoredAndDeletedByBot() {
        dataSource = new SingleConnectionDataSource(
                "jdbc:sqlite:" + tempDir.resolve("contexts.db"), true);
        ConversationContextStore store = new ConversationContextStore(new JdbcTemplate(dataSource));
        store.initSchema();

        store.saveIncomingContext("bot-a", "user-a", "token-1", 100L, 200L);
        Map<String, ConversationContext> contexts = store.findByBotId("bot-a");
        assertEquals("token-1", contexts.get("user-a").getLatestContextToken());

        ConversationContext context = contexts.get("user-a");
        context.setTypingTicket("typing-1");
        store.saveSnapshots("bot-a", contexts.values());

        Map<String, ConversationContext> restored = store.findByBotId("bot-a");
        assertEquals("token-1", restored.get("user-a").getLatestContextToken());
        assertEquals("typing-1", restored.get("user-a").getTypingTicket());

        store.deleteByBotId("bot-a");
        assertTrue(store.findByBotId("bot-a").isEmpty());
    }
}
