package com.demo.WechatBot.service;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 用户画像提取器。
 * <p>
 * 当对话消息被挤出窗口时，异步调用 LLM 从中提取关于用户的关键信息，
 * 更新到 UserProfileStore。单线程串行执行，避免并发写冲突。
 * </p>
 */
public class ProfileExtractor {

    private static final Logger log = LoggerFactory.getLogger(ProfileExtractor.class);

    private static final String EXTRACTION_PROMPT = """
            你是一个信息提取助手。请从以下对话片段中提取关于用户的信息，更新到现有画像中。

            【现有画像】
            %s

            【对话片段】
            %s

            请输出更新后的完整画像。格式要求：
            - 每行一个信息点，用"标签：内容"格式（如"名字：小杰"）
            - 只保留对话中明确出现的信息，不猜测
            - 新信息添加到末尾，已有信息如有更新则替换
            - 保持简洁，每行不超过30字
            - 如果对话片段中没有关于用户的新信息，原样返回现有画像

            只输出画像内容，不要输出其他任何文字。
            """;

    private final ChatLanguageModel model;
    private final UserProfileStore profileStore;
    private final ConversationIdentityService identityService;
    // 单线程串行执行，避免同一 session 的并发画像更新冲突
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    public ProfileExtractor(ChatLanguageModel model, UserProfileStore profileStore,
                            ConversationIdentityService identityService) {
        this.model = model;
        this.profileStore = profileStore;
        this.identityService = identityService;
    }

    /**
     * 异步提取并更新用户画像。
     * <p>
     * 将被挤出的对话消息格式化为文本，连同现有画像一起发给 LLM，
     * 让模型提取关键信息并返回更新后的画像。
     * </p>
     *
     * @param sessionId        会话 ID
     * @param evictedMessages  被挤出窗口的消息列表
     */
    public void updateProfileAsync(String sessionId, List<ChatMessage> evictedMessages) {
        executor.submit(() -> {
            try {
                String currentProfile = profileStore.getProfile(sessionId);
                if (currentProfile == null) {
                    currentProfile = "（暂无）";
                }

                String conversation = formatMessages(sessionId, evictedMessages);
                String prompt = String.format(EXTRACTION_PROMPT, currentProfile, conversation);

                log.info("提取用户画像 [session={}], 挤出消息 {} 条", sessionId, evictedMessages.size());
                String updatedProfile = model.generate(prompt);

                if (updatedProfile != null && !updatedProfile.isBlank()) {
                    profileStore.saveProfile(sessionId, updatedProfile.trim());
                    log.info("用户画像已更新 [session={}]", sessionId);
                }
            } catch (Exception e) {
                log.error("提取用户画像失败 [session={}]", sessionId, e);
            }
        });
    }

    public void shutdown() {
        executor.shutdown();
    }

    /**
     * 将消息列表格式化为可读的对话文本
     */
    private String formatMessages(String sessionId, List<ChatMessage> messages) {
        ConversationIdentityService.ConversationIdentity identity = identityService.forSession(sessionId);
        StringBuilder sb = new StringBuilder();
        for (ChatMessage msg : messages) {
            if (msg instanceof UserMessage um) {
                sb.append(identity.userLabel()).append(": ").append(um.text());
            } else if (msg instanceof AiMessage am) {
                sb.append(identity.botLabel()).append(": ").append(am.text());
            } else {
                sb.append(msg.type()).append(": ").append(msg);
            }
            sb.append("\n");
        }
        return sb.toString().trim();
    }
}
