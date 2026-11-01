package com.demo.WechatBot;

import com.demo.WechatBot.service.BotPersonaStore;
import com.demo.WechatBot.service.OnboardingService;
import dev.langchain4j.model.chat.ChatLanguageModel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OnboardingFlowIntegrationTest {

    private static final String SESSION = "bot:user";

    @TempDir
    Path tempDir;

    private SingleConnectionDataSource dataSource;

    @AfterEach
    void tearDown() {
        if (dataSource != null) dataSource.destroy();
    }

    @Test
    void completeFlowPersistsProgressAndWaitsForExplicitConfirmation() {
        BotPersonaStore store = openStore("flow.db");
        store.initSchema();
        ChatLanguageModel model = mock(ChatLanguageModel.class);
        OnboardingService service = new OnboardingService(model, store);

        String welcome = service.handle(SESSION, "你是谁", null);
        assertTrue(welcome.contains("第一步"));
        assertEquals(OnboardingService.STEP_NAME, store.find(SESSION).onboardingStep());

        service.handle(SESSION, "林晚", store.find(SESSION));
        service.handle(SESSION, "女生", store.find(SESSION));

        // 重新创建服务模拟应用重启，已确认字段和当前步骤仍从 SQLite 恢复。
        service = new OnboardingService(model, store);
        assertEquals(OnboardingService.STEP_PERSONALITY, store.find(SESSION).onboardingStep());
        service.handle(SESSION, "慢热但靠谱", store.find(SESSION));
        service.handle(SESSION, "跳过", store.find(SESSION));

        when(model.generate(anyString())).thenReturn(
                "你是林晚，一个慢热但靠谱的女生，说话简短温柔。", "你好，我是林晚");
        String summary = service.handle(SESSION, "简短温柔", store.find(SESSION));

        BotPersonaStore.Persona pending = store.find(SESSION);
        assertEquals(BotPersonaStore.STATUS_CONFIRMING, pending.status());
        assertTrue(summary.contains("我的名字：林晚"));
        assertTrue(summary.contains("性格：慢热但靠谱"));
        assertFalse(summary.contains("关系："));
        assertFalse(store.isReady(pending));

        String originalPersona = pending.personaText();
        String clarification = service.handle(SESSION, "我没有告诉你我叫林晚啊", store.find(SESSION));
        assertTrue(clarification.contains("是我的名字，不是你的"));
        assertEquals(originalPersona, store.find(SESSION).personaText());
        assertEquals(BotPersonaStore.STATUS_CONFIRMING, store.find(SESSION).status());
        verify(model, times(1)).generate(anyString());

        String greeting = service.handle(SESSION, "确认", store.find(SESSION));
        assertEquals("你好，我是林晚", greeting);
        assertTrue(store.isReady(SESSION));
        assertEquals("READY", store.find(SESSION).onboardingStep());
        verify(model, times(2)).generate(anyString());
    }

    @Test
    void legacySchemaIsMigratedWithoutLosingExistingRecord() {
        BotPersonaStore store = openStore("legacy.db");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                CREATE TABLE bot_personas (
                    session_key TEXT PRIMARY KEY,
                    status TEXT NOT NULL DEFAULT 'ONBOARDING',
                    name TEXT,
                    gender TEXT,
                    persona_text TEXT,
                    draft_context TEXT,
                    tts_voice TEXT,
                    created_at TEXT NOT NULL DEFAULT (datetime('now', 'localtime')),
                    updated_at TEXT NOT NULL DEFAULT (datetime('now', 'localtime'))
                )
                """);
        jdbc.update("INSERT INTO bot_personas (session_key, status, draft_context) VALUES (?, 'ONBOARDING', ?)",
                SESSION, "用户：旧草稿");

        store.initSchema();

        BotPersonaStore.Persona restored = store.find(SESSION);
        assertEquals(OnboardingService.STEP_NAME, restored.onboardingStep());
        assertEquals("{}", restored.draftFields());
        assertEquals("用户：旧草稿", restored.draftContext());
        var columns = jdbc.queryForList("PRAGMA table_info(bot_personas)");
        assertTrue(columns.stream().noneMatch(column -> "tts_voice".equals(column.get("name"))));
    }

    private BotPersonaStore openStore(String filename) {
        dataSource = new SingleConnectionDataSource(
                "jdbc:sqlite:" + tempDir.resolve(filename), true);
        return new BotPersonaStore(new JdbcTemplate(dataSource));
    }
}
