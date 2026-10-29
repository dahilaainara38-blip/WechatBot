package com.demo.WechatBot.service;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * LangChain4j ChatMemoryStore 的 SQLite 实现。
 * <p>
 * 每个会话窗口序列化为一行 JSON，由框架在对话前后自动读写。
 * 重启后首次对话时 getMessages 从 SQLite 恢复窗口，实现跨重启记忆。
 * </p>
 */
@Component
public class SQLiteChatMemoryStore implements ChatMemoryStore {

    private static final Logger log = LoggerFactory.getLogger(SQLiteChatMemoryStore.class);

    private final JdbcTemplate jdbc;

    public SQLiteChatMemoryStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        String json = jdbc.query(
                "SELECT messages_json FROM chat_memory WHERE session_id = ?",
                (rs, rowNum) -> rs.getString("messages_json"),
                memoryId
        ).stream().findFirst().orElse(null);

        if (json == null) {
            log.debug("无历史记忆 [session={}]", memoryId);
            return List.of();
        }

        List<ChatMessage> messages = ChatMessageDeserializer.messagesFromJson(json);
        log.debug("恢复记忆 [session={}]: {} 条消息", memoryId, messages.size());
        return messages;
    }

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        String json = ChatMessageSerializer.messagesToJson(messages);
        int affected = jdbc.update(
                "UPDATE chat_memory SET messages_json = ?, updated_at = datetime('now', 'localtime') WHERE session_id = ?",
                json, memoryId
        );
        if (affected == 0) {
            jdbc.update(
                    "INSERT INTO chat_memory (session_id, messages_json) VALUES (?, ?)",
                    memoryId, json
            );
        }
        log.debug("持久化记忆 [session={}]: {} 条消息", memoryId, messages.size());
    }

    @Override
    public void deleteMessages(Object memoryId) {
        jdbc.update("DELETE FROM chat_memory WHERE session_id = ?", memoryId);
        log.info("清除记忆 [session={}]", memoryId);
    }
}
