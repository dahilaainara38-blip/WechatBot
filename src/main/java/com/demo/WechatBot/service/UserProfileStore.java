package com.demo.WechatBot.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 用户画像存储层。
 * <p>
 * 每个 session 一行，存储半结构化的用户画像文本。
 * 由 ProfileExtractor 在消息被挤出窗口时异步更新。
 * </p>
 */
@Component
public class UserProfileStore {

    private static final Logger log = LoggerFactory.getLogger(UserProfileStore.class);

    private final JdbcTemplate jdbc;

    public UserProfileStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 读取用户画像，不存在返回 null
     */
    public String getProfile(String sessionId) {
        return jdbc.query(
                "SELECT profile FROM user_profiles WHERE session_id = ?",
                (rs, rowNum) -> rs.getString("profile"),
                sessionId
        ).stream().findFirst().orElse(null);
    }

    /**
     * 保存或更新用户画像（UPSERT）
     */
    public void saveProfile(String sessionId, String profile) {
        int affected = jdbc.update(
                "UPDATE user_profiles SET profile = ?, updated_at = datetime('now', 'localtime') WHERE session_id = ?",
                profile, sessionId
        );
        if (affected == 0) {
            jdbc.update(
                    "INSERT INTO user_profiles (session_id, profile) VALUES (?, ?)",
                    sessionId, profile
            );
        }
        log.debug("用户画像已更新 [session={}]", sessionId);
    }
}
