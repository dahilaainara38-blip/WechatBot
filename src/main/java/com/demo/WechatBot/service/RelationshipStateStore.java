package com.demo.WechatBot.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 关系状态存储层。
 * <p>
 * 每个 session 一行，存储 AI 分析出的关系状态文本 + 最后互动时间戳。
 * 由 RelationshipStateExtractor 在每轮对话后异步更新。
 * </p>
 */
@Component
public class RelationshipStateStore {

    private static final Logger log = LoggerFactory.getLogger(RelationshipStateStore.class);

    private final JdbcTemplate jdbc;

    public RelationshipStateStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 读取关系状态，不存在返回 null
     */
    public String getState(String sessionId) {
        return jdbc.query(
                "SELECT state_text FROM relationship_states WHERE session_id = ?",
                (rs, rowNum) -> rs.getString("state_text"),
                sessionId
        ).stream().findFirst().orElse(null);
    }

    /**
     * 读取最后互动时间戳（ISO 格式字符串），不存在返回 null
     */
    public String getLastInteractionAt(String sessionId) {
        return jdbc.query(
                "SELECT last_interaction_at FROM relationship_states WHERE session_id = ?",
                (rs, rowNum) -> rs.getString("last_interaction_at"),
                sessionId
        ).stream().findFirst().orElse(null);
    }

    /**
     * 保存或更新关系状态（UPSERT），同时刷新最后互动时间
     */
    public void saveState(String sessionId, String stateText) {
        int affected = jdbc.update(
                "UPDATE relationship_states SET state_text = ?, last_interaction_at = datetime('now', 'localtime'), updated_at = datetime('now', 'localtime') WHERE session_id = ?",
                stateText, sessionId
        );
        if (affected == 0) {
            jdbc.update(
                    "INSERT INTO relationship_states (session_id, state_text) VALUES (?, ?)",
                    sessionId, stateText
            );
        }
        log.debug("关系状态已更新 [session={}]", sessionId);
    }

    /**
     * 仅刷新最后互动时间（不更新状态文本）
     */
    public void touchInteraction(String sessionId) {
        jdbc.update(
                "UPDATE relationship_states SET last_interaction_at = datetime('now', 'localtime') WHERE session_id = ?",
                sessionId
        );
    }
}
