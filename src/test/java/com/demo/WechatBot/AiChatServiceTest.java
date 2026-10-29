package com.demo.WechatBot;

import com.demo.WechatBot.service.AiChatService;
import com.demo.WechatBot.service.RelationshipStateStore;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "RUN_EXTERNAL_AI_TESTS", matches = "true")
class AiChatServiceTest {

    private static final Logger log = LoggerFactory.getLogger(AiChatServiceTest.class);

    @Autowired
    private AiChatService aiChatService;

    @Autowired
    private RelationshipStateStore relationshipStateStore;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void testBasicChat() {
        String sessionId = "test-basic";
        cleanup(sessionId);
        markPersonaReady(sessionId);
        String reply = aiChatService.chat(sessionId, "在吗");
        log.info("=== TEST 1: Basic Chat ===");
        log.info("Reply: {}", reply);
        assertNotNull(reply);
        assertFalse(reply.isBlank());
        assertFalse(reply.contains("AI"));
        assertFalse(reply.contains("help"));
    }

    @Test
    void testReunionDirective() {
        String sessionId = "test-reunion";
        cleanup(sessionId);
        markPersonaReady(sessionId);
        relationshipStateStore.saveState(sessionId,
                "mood: calm\ntemp: warm\nevent: none\nnote: normal");
        setOldInteractionTime(sessionId, 8);
        String reply = aiChatService.chat(sessionId, "class over");
        log.info("=== TEST 2: Reunion (8h gap) ===");
        log.info("Reply: {}", reply);
        assertNotNull(reply);
        assertFalse(reply.isBlank());
    }

    @Test
    void testColdStateBehavior() {
        String sessionId = "test-cold";
        cleanup(sessionId);
        markPersonaReady(sessionId);
        relationshipStateStore.saveState(sessionId,
                "mood: unhappy\ntemp: cold\nevent: slow reply\nnote: upset");
        relationshipStateStore.touchInteraction(sessionId);
        String reply = aiChatService.chat(sessionId, "what's wrong");
        log.info("=== TEST 3: Cold State ===");
        log.info("Reply: {}", reply);
        assertNotNull(reply);
        assertFalse(reply.isBlank());
    }

    @Test
    void testPhilosophyTopic() {
        String sessionId = "test-philosophy";
        cleanup(sessionId);
        markPersonaReady(sessionId);
        String reply = aiChatService.chat(sessionId, "what is the meaning of life");
        log.info("=== TEST 4: Philosophy ===");
        log.info("Reply: {}", reply);
        assertNotNull(reply);
        assertFalse(reply.isBlank());
    }

    private void setOldInteractionTime(String sessionId, int hoursAgo) {
        String oldTime = LocalDateTime.now().minusHours(hoursAgo)
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        jdbc.update("UPDATE relationship_states SET last_interaction_at = ? WHERE session_id = ?",
                oldTime, sessionId);
    }

    private void cleanup(String sessionId) {
        jdbc.update("DELETE FROM bot_personas WHERE session_key = ?", sessionId);
        jdbc.update("DELETE FROM chat_memory WHERE session_id = ?", sessionId);
        jdbc.update("DELETE FROM relationship_states WHERE session_id = ?", sessionId);
        jdbc.update("DELETE FROM user_profiles WHERE session_id = ?", sessionId);
        jdbc.update("DELETE FROM conversations WHERE session_id = ?", sessionId);
    }

    private void markPersonaReady(String sessionId) {
        jdbc.update("""
                        INSERT INTO bot_personas
                            (session_key, status, name, gender, persona_text, draft_context)
                        VALUES (?, 'READY', '测试伙伴', '其他', ?, '')
                        """,
                sessionId, "你是测试伙伴，用自然、简短的口语和用户聊天。");
    }

    @AfterAll
    static void cleanupAll(@Autowired JdbcTemplate jdbc) {
        for (String s : new String[]{"test-basic", "test-reunion", "test-cold", "test-philosophy"}) {
            jdbc.update("DELETE FROM bot_personas WHERE session_key = ?", s);
            jdbc.update("DELETE FROM chat_memory WHERE session_id = ?", s);
            jdbc.update("DELETE FROM relationship_states WHERE session_id = ?", s);
            jdbc.update("DELETE FROM user_profiles WHERE session_id = ?", s);
            jdbc.update("DELETE FROM conversations WHERE session_id = ?", s);
        }
    }
}
