package com.demo.WechatBot.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 统一销毁 bot 或会话相关数据，避免各存储层分别遗漏。
 */
@Service
public class ConversationDataCleanupService {

    private static final Logger log = LoggerFactory.getLogger(ConversationDataCleanupService.class);

    private final JdbcTemplate jdbc;

    public ConversationDataCleanupService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 删除 bot 登录态及其名下全部会话数据。调用前必须已完成 owner 校验。
     *
     * @return 是否找到并删除了该 owner 名下的 bot
     */
    @Transactional
    public boolean deleteBotCompletely(String botId, String ownerId) {
        int accounts = jdbc.update(
                "DELETE FROM bot_accounts WHERE bot_id = ? AND owner_id = ?", botId, ownerId);
        if (accounts == 0) {
            return false;
        }

        String sessionPrefix = botId + ":%";
        int personas = jdbc.update(
                "DELETE FROM bot_personas WHERE session_key LIKE ?", sessionPrefix);
        int chatMemory = jdbc.update(
                "DELETE FROM chat_memory WHERE session_id LIKE ?", sessionPrefix);
        int conversations = jdbc.update(
                "DELETE FROM conversations WHERE session_id LIKE ?", sessionPrefix);
        int profiles = jdbc.update(
                "DELETE FROM user_profiles WHERE session_id LIKE ?", sessionPrefix);
        int states = jdbc.update(
                "DELETE FROM relationship_states WHERE session_id LIKE ?", sessionPrefix);
        int contexts = jdbc.update(
                "DELETE FROM conversation_contexts WHERE bot_id = ?", botId);

        log.info("机器人数据已彻底删除 [botId={}, personas={}, chatMemory={}, conversations={}, " +
                        "profiles={}, relationshipStates={}, contexts={}]",
                botId, personas, chatMemory, conversations, profiles, states, contexts);
        return true;
    }
}
