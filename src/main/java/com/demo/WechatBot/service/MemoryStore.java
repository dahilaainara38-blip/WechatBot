package com.demo.WechatBot.service;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 长期记忆存储层
 * <p>
 * 基于 SQLite 持久化对话记录，为后续 RAG 检索提供数据源。
 * 每次 AI 对话完成后自动保存 user 和 assistant 消息。
 * </p>
 */
@Repository
public class MemoryStore {

    private static final Logger log = LoggerFactory.getLogger(MemoryStore.class);

    private final JdbcTemplate jdbc;

    public MemoryStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    public void initSchema() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS conversations (
                    id          INTEGER PRIMARY KEY AUTOINCREMENT,
                    session_id  TEXT    NOT NULL,
                    role        TEXT    NOT NULL,
                    content     TEXT    NOT NULL,
                    created_at  TEXT    NOT NULL DEFAULT (datetime('now', 'localtime'))
                )
                """);
        jdbc.execute("""
                CREATE INDEX IF NOT EXISTS idx_conversations_session
                ON conversations (session_id, id)
                """);
        // 会话窗口持久化表：每个 session 一行，存序列化后的完整窗口
        jdbc.execute("""
               CREATE TABLE IF NOT EXISTS chat_memory (
                   session_id      TEXT PRIMARY KEY,
                   messages_json   TEXT NOT NULL,
                   updated_at      TEXT NOT NULL DEFAULT (datetime('now', 'localtime'))
               )
               """);
        // 用户画像表：每个 session 一行，存半结构化画像文本
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS user_profiles (
                    session_id  TEXT PRIMARY KEY,
                    profile     TEXT NOT NULL,
                    updated_at  TEXT NOT NULL DEFAULT (datetime('now', 'localtime'))
                )
                """);
        // 关系状态表：每个 session 一行，存 AI 分析的关系动态状态
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS relationship_states (
                    session_id          TEXT PRIMARY KEY,
                    state_text          TEXT NOT NULL,
                    last_interaction_at TEXT NOT NULL DEFAULT (datetime('now', 'localtime')),
                    updated_at          TEXT NOT NULL DEFAULT (datetime('now', 'localtime'))
                )
                """);
        log.info("SQLite memory store initialized");
    }

    /**
     * 保存一条对话记录
     */
    public void save(String sessionId, String role, String content) {
        jdbc.update(
                "INSERT INTO conversations (session_id, role, content) VALUES (?, ?, ?)",
                sessionId, role, content);
    }

    /**
     * 查询某个用户最近的 N 条对话记录
     */
    public List<String> findRecent(String sessionId, int limit) {
        return jdbc.query(
                "SELECT content FROM conversations WHERE session_id = ? ORDER BY id DESC LIMIT ?",
                (rs, row) -> rs.getString("content"),
                sessionId, limit);
    }

    /**
     * 查询某个用户的所有对话记录（用于重建 Embedding 索引）
     */
    public List<String> findAllBySessionId(String sessionId) {
        return jdbc.query(
                "SELECT content FROM conversations WHERE session_id = ? ORDER BY id ASC",
                (rs, row) -> rs.getString("content"),
                sessionId);
    }

    /**
     * 查询所有有过对话记录的用户（用于主动消息推送）
     */
    public List<String> findActiveSessions() {
        return jdbc.queryForList(
                "SELECT DISTINCT session_id FROM conversations",
                String.class);
    }
}
