package com.demo.WechatBot.service;

import dev.langchain4j.model.chat.ChatLanguageModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 关系状态提取器。
 * <p>
 * 每轮对话后异步调用 LLM，分析这轮对话对关系状态的影响并更新。
 * 单线程串行执行，避免并发写冲突。
 * </p>
 */
public class RelationshipStateExtractor {

    private static final Logger log = LoggerFactory.getLogger(RelationshipStateExtractor.class);

    private static final String ANALYSIS_PROMPT = """
            你是一个关系状态分析助手。请根据这轮对话更新关系状态。

            【当前关系状态】
            %s

            【这轮对话】
            %s: %s
            %s: %s

            请分析这轮对话后关系状态的变化，输出更新后的状态。格式要求：
            每行一个维度，用"标签：内容"格式：
            - 当前心情：（%s 当前的心情和感受）
            - 关系温度：（冷淡/微凉/平常/温暖/甜蜜，选一个）
            - 最近事件：（最近发生的影响关系的事，简短）
            - 情绪备注：（%s 对这段关系的即时感受和倾向）

            规则：
            - 如果对话很日常，状态可能几乎不变，但心情要反映这轮对话的即时情绪
            - 如果%s说了冷淡的话，温度下降；如果甜蜜，温度上升
            - 状态要连贯，基于当前状态演进，不要突变
            - 每行不超过40字
            - 只输出状态内容，不要其他文字
            """;

    private final ChatLanguageModel model;
    private final RelationshipStateStore stateStore;
    private final ConversationIdentityService identityService;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    public RelationshipStateExtractor(ChatLanguageModel model, RelationshipStateStore stateStore,
                                      ConversationIdentityService identityService) {
        this.model = model;
        this.stateStore = stateStore;
        this.identityService = identityService;
    }

    /**
     * 异步更新关系状态。
     *
     * @param sessionId     会话 ID
     * @param userMessage   用户这轮说的话
     * @param aiReply       AI 这轮的回复
     */
    public void updateStateAsync(String sessionId, String userMessage, String aiReply) {
        executor.submit(() -> {
            try {
                String currentState = stateStore.getState(sessionId);
                if (currentState == null) {
                    currentState = "当前心情：平静\\n关系温度：平常\\n最近事件：无\\n情绪备注：刚开始聊天";
                }

                ConversationIdentityService.ConversationIdentity identity = identityService.forSession(sessionId);
                String prompt = String.format(
                        ANALYSIS_PROMPT, currentState,
                        identity.userLabel(), userMessage,
                        identity.botLabel(), aiReply,
                        identity.botLabel(), identity.botLabel(), identity.userLabel());

                log.info("更新关系状态 [session={}]", sessionId);
                String updatedState = model.generate(prompt);

                if (updatedState != null && !updatedState.isBlank()) {
                    stateStore.saveState(sessionId, updatedState.trim());
                    log.info("关系状态已更新 [session={}]", sessionId);
                }
            } catch (Exception e) {
                log.error("更新关系状态失败 [session={}]", sessionId, e);
            }
        });
    }

    public void shutdown() {
        executor.shutdown();
    }
}
