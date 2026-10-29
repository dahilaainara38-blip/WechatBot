package com.demo.WechatBot.service;

import com.github.wechat.ilink.sdk.core.context.ContextKey;
import com.github.wechat.ilink.sdk.core.context.ConversationContext;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 持久化 iLink 会话上下文，使服务重启后仍能向已有会话发送主动消息。
 */
@Component
public class ConversationContextStore {

    private static final Logger log = LoggerFactory.getLogger(ConversationContextStore.class);

    public record StoredContext(String sessionKey, String botId, String userId,
                                String contextToken, String typingTicket,
                                Long sourceMessageId, Long sourceMessageTime) {
    }

    private final JdbcTemplate jdbc;

    public ConversationContextStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    public void initSchema() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS conversation_contexts (
                    session_key          TEXT PRIMARY KEY,
                    bot_id               TEXT NOT NULL,
                    user_id              TEXT NOT NULL,
                    context_token        TEXT,
                    typing_ticket        TEXT,
                    source_message_id    INTEGER,
                    source_message_time  INTEGER,
                    updated_at           TEXT NOT NULL DEFAULT (datetime('now', 'localtime'))
                )
                """);
        jdbc.execute("""
                CREATE INDEX IF NOT EXISTS idx_conversation_contexts_bot
                ON conversation_contexts (bot_id)
                """);
    }

    public void saveIncomingContext(String botId, String userId, String contextToken,
                                    Long messageId, Long messageTime) {
        if (contextToken == null || contextToken.isBlank()) return;
        jdbc.update("""
                        INSERT INTO conversation_contexts
                            (session_key, bot_id, user_id, context_token, source_message_id, source_message_time)
                        VALUES (?, ?, ?, ?, ?, ?)
                        ON CONFLICT(session_key) DO UPDATE SET
                            context_token = excluded.context_token,
                            source_message_id = excluded.source_message_id,
                            source_message_time = excluded.source_message_time,
                            updated_at = datetime('now', 'localtime')
                        """,
                sessionKey(botId, userId), botId, userId, contextToken, messageId, messageTime);
    }

    public void saveSnapshots(String botId, Collection<ConversationContext> contexts) {
        if (contexts == null) return;
        for (ConversationContext context : contexts) {
            if (context == null || context.getKey() == null) continue;
            String contextBotId = context.getKey().getBotId();
            String userId = context.getKey().getUserId();
            if (!botId.equals(contextBotId) || userId == null) continue;
            if (!context.hasContextToken() && context.getTypingTicket() == null) continue;
            jdbc.update("""
                            INSERT INTO conversation_contexts
                                (session_key, bot_id, user_id, context_token, typing_ticket,
                                 source_message_id, source_message_time)
                            VALUES (?, ?, ?, ?, ?, ?, ?)
                            ON CONFLICT(session_key) DO UPDATE SET
                                context_token = excluded.context_token,
                                typing_ticket = excluded.typing_ticket,
                                source_message_id = excluded.source_message_id,
                                source_message_time = excluded.source_message_time,
                                updated_at = datetime('now', 'localtime')
                            """,
                    sessionKey(botId, userId), botId, userId,
                    context.getLatestContextToken(), context.getTypingTicket(),
                    context.getSourceMessageId(), context.getSourceMessageTime());
        }
        log.debug("会话上下文快照已保存 [botId={}, count={}]", botId, contexts.size());
    }

    public Map<String, ConversationContext> findByBotId(String botId) {
        Map<String, ConversationContext> result = new LinkedHashMap<>();
        jdbc.query("""
                        SELECT user_id, context_token, typing_ticket
                        FROM conversation_contexts WHERE bot_id = ?
                        """,
                rs -> {
                    String userId = rs.getString("user_id");
                    ConversationContext context = new ConversationContext(new ContextKey(botId, userId));
                    String token = rs.getString("context_token");
                    String ticket = rs.getString("typing_ticket");
                    if (token != null && !token.isBlank()) {
                        context.setLatestContextToken(token);
                    }
                    if (ticket != null && !ticket.isBlank()) {
                        context.setTypingTicket(ticket);
                    }
                    result.put(userId, context);
                }, botId);
        return result;
    }

    public void deleteByBotId(String botId) {
        jdbc.update("DELETE FROM conversation_contexts WHERE bot_id = ?", botId);
    }

    public String sessionKey(String botId, String userId) {
        return botId + ":" + userId;
    }
}
