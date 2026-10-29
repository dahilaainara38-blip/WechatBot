package com.demo.WechatBot.service;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 机器人人设卡存储层。
 * <p>
 * 每个会话（botId:fromUserId）一行，存储用户自定义的机器人人设。
 * 状态机：ONBOARDING（引导中）→ CONFIRMING（待用户确认）→ READY（生效）。
 * 引导过程中的对话草稿持久化在 draft_context，重启后可以继续引导。
 * </p>
 */
@Component
public class BotPersonaStore {

    private static final Logger log = LoggerFactory.getLogger(BotPersonaStore.class);

    public static final String STATUS_ONBOARDING = "ONBOARDING";
    public static final String STATUS_CONFIRMING = "CONFIRMING";
    public static final String STATUS_READY = "READY";

    public record Persona(String sessionKey, String status, String name, String gender,
                          String personaText, String draftContext, String ttsVoice) {
    }

    private final JdbcTemplate jdbc;

    /** 人设名缓存，供各提取器高频查询对话标签用 */
    private final Map<String, String> nameCache = new ConcurrentHashMap<>();

    public BotPersonaStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    public void initSchema() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS bot_personas (
                    session_key     TEXT PRIMARY KEY,
                    status          TEXT NOT NULL DEFAULT 'ONBOARDING',
                    name            TEXT,
                    gender          TEXT,
                    persona_text    TEXT,
                    draft_context   TEXT,
                    tts_voice       TEXT,
                    created_at      TEXT NOT NULL DEFAULT (datetime('now', 'localtime')),
                    updated_at      TEXT NOT NULL DEFAULT (datetime('now', 'localtime'))
                )
                """);
        log.info("Bot persona store initialized");
    }

    public Persona find(String sessionKey) {
        return jdbc.query(
                "SELECT session_key, status, name, gender, persona_text, draft_context, tts_voice " +
                        "FROM bot_personas WHERE session_key = ?",
                (rs, rowNum) -> new Persona(
                        rs.getString("session_key"),
                        rs.getString("status"),
                        rs.getString("name"),
                        rs.getString("gender"),
                        rs.getString("persona_text"),
                        rs.getString("draft_context"),
                        rs.getString("tts_voice")),
                sessionKey
        ).stream().findFirst().orElse(null);
    }

    /**
     * 首次进入引导：创建 ONBOARDING 记录（已存在则忽略）
     */
    public void createOnboarding(String sessionKey) {
        jdbc.update(
                "INSERT OR IGNORE INTO bot_personas (session_key, status, draft_context) VALUES (?, ?, '')",
                sessionKey, STATUS_ONBOARDING
        );
    }

    /**
     * 更新引导对话草稿
     */
    public void updateDraft(String sessionKey, String draftContext) {
        jdbc.update(
                "UPDATE bot_personas SET draft_context = ?, updated_at = datetime('now', 'localtime') WHERE session_key = ?",
                draftContext, sessionKey
        );
    }

    /**
     * 人设卡已生成，等待用户确认（ONBOARDING/CONFIRMING → CONFIRMING）
     */
    public void savePending(String sessionKey, String name, String gender, String personaText, String ttsVoice) {
        jdbc.update(
                "UPDATE bot_personas SET status = ?, name = ?, gender = ?, persona_text = ?, tts_voice = ?, " +
                        "updated_at = datetime('now', 'localtime') WHERE session_key = ?",
                STATUS_CONFIRMING, name, gender, personaText, ttsVoice, sessionKey
        );
        nameCache.put(sessionKey, name);
        log.info("人设卡待确认 [session={}, name={}]", sessionKey, name);
    }

    /**
     * 用户确认，人设正式生效（CONFIRMING → READY）
     */
    public void markReady(String sessionKey) {
        jdbc.update(
                "UPDATE bot_personas SET status = ?, updated_at = datetime('now', 'localtime') WHERE session_key = ?",
                STATUS_READY, sessionKey
        );
        log.info("人设已生效 [session={}]", sessionKey);
    }

    /**
     * 重置回引导态：清空人设与草稿，保留记录
     */
    public void resetToOnboarding(String sessionKey) {
        jdbc.update(
                "UPDATE bot_personas SET status = ?, name = NULL, gender = NULL, persona_text = NULL, " +
                        "draft_context = '', tts_voice = NULL, updated_at = datetime('now', 'localtime') WHERE session_key = ?",
                STATUS_ONBOARDING, sessionKey
        );
        nameCache.remove(sessionKey);
        log.info("人设已重置回引导态 [session={}]", sessionKey);
    }

    public boolean isReady(Persona persona) {
        return persona != null && STATUS_READY.equals(persona.status());
    }

    public boolean isReady(String sessionKey) {
        Persona p = find(sessionKey);
        return isReady(p);
    }

    /**
     * 读取人设正文（READY 会话的 system prompt 用）
     */
    public String getPersonaText(String sessionKey) {
        return jdbc.query(
                "SELECT persona_text FROM bot_personas WHERE session_key = ? AND status = ?",
                (rs, rowNum) -> rs.getString("persona_text"),
                sessionKey, STATUS_READY
        ).stream().findFirst().orElse(null);
    }

    /**
     * 读取人设名（对话标签用），带缓存；无人设时返回默认标签
     */
    public String getName(String sessionKey) {
        return nameCache.computeIfAbsent(sessionKey, k ->
                jdbc.query(
                        "SELECT name FROM bot_personas WHERE session_key = ?",
                        (rs, rowNum) -> rs.getString("name"),
                        k
                ).stream().findFirst().filter(n -> n != null && !n.isBlank()).orElse("对方")
        );
    }

    /**
     * 读取人设对应的 TTS 音色；未设置返回 null（调用方用默认音色）
     */
    public String getTtsVoice(String sessionKey) {
        return jdbc.query(
                "SELECT tts_voice FROM bot_personas WHERE session_key = ?",
                (rs, rowNum) -> rs.getString("tts_voice"),
                sessionKey
        ).stream().findFirst().filter(v -> v != null && !v.isBlank()).orElse(null);
    }

    public void delete(String sessionKey) {
        jdbc.update("DELETE FROM bot_personas WHERE session_key = ?", sessionKey);
        nameCache.remove(sessionKey);
    }
}
