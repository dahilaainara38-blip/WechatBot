package com.demo.WechatBot.service;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 状态感知的对话记忆。
 * <p>
 * 直接实现 ChatMemory 接口，替代 MessageWindowChatMemory + ProfileAwareChatMemory 的组合。
 * 维护一个短窗口（默认8条）的原始消息 + 一个结构化的滚动状态摘要。
 * </p>
 * <p>
 * 摘要以 SystemMessage 形式存储在记忆头部，跟踪"当前状态"而非"对话历史"：
 * 新信息覆盖旧信息（如"还没吃饭"→"已吃午饭"），已结束的话题标记为已结束，
 * 避免过时信息残留导致模型重复讨论已过去的事。
 * </p>
 * <p>
 * 摘要生成是异步的：add() 先用旧摘要快速持久化（不阻塞对话响应），
 * 后台生成新摘要后再写回 store。通过 synchronized 保证 store 读写安全。
 * </p>
 */
public class StatefulChatMemory implements ChatMemory {

    private static final Logger log = LoggerFactory.getLogger(StatefulChatMemory.class);

    /** 摘要 SystemMessage 的前缀，用于识别记忆中的摘要消息 */
    private static final String SUMMARY_PREFIX = "【对话状态摘要】";

    private static final String SUMMARY_PROMPT = """
            请更新对话状态摘要。根据新对话更新当前状态，覆盖过时信息。

            【当前摘要】
            %s

            【新对话】
            %s

            输出格式：
            当前状态：
            - 话题：当前值
            - 话题：当前值
            近期话题：简述最近在聊什么
            已结束：列出已经聊完的话题，简短

            规则：
            - 新信息直接覆盖旧信息（如"还没吃饭"被"已吃午饭"覆盖，旧值消失）
            - 移除完全过时且不再相关的信息
            - 保留重要的长期背景信息
            - 每条不超过30字
            - 总计不超过200字
            - 只输出摘要，不要其他文字
            """;

    private final Object id;
    private final ChatMemoryStore store;
    private final ChatLanguageModel model;
    private final ProfileExtractor profileExtractor;
    private final ConversationIdentityService identityService;
    private final int maxBufferMessages;

    /** 异步摘要生成线程池，单线程串行 */
    private final ExecutorService summaryExecutor = Executors.newSingleThreadExecutor();

    public StatefulChatMemory(Object id, ChatMemoryStore store, ChatLanguageModel model,
                              ProfileExtractor profileExtractor,
                              ConversationIdentityService identityService,
                              int maxBufferMessages) {
        this.id = id;
        this.store = store;
        this.model = model;
        this.profileExtractor = profileExtractor;
        this.identityService = identityService;
        this.maxBufferMessages = maxBufferMessages;
    }

    @Override
    public Object id() {
        return id;
    }

    @Override
    public synchronized void add(ChatMessage message) {
        // 从 store 加载当前记忆
        List<ChatMessage> all = store.getMessages(id);

        // 分离摘要和原始消息缓冲区
        String currentSummary = null;
        List<ChatMessage> buffer = new ArrayList<>();
        for (ChatMessage msg : all) {
            if (isSummaryMessage(msg)) {
                currentSummary = extractSummaryText(msg);
            } else {
                buffer.add(msg);
            }
        }

        // 添加新消息
        buffer.add(message);

        // 收集所有被挤出的消息（成对挤出，保持对话完整性）
        List<ChatMessage> allEvicted = new ArrayList<>();
        while (buffer.size() > maxBufferMessages) {
            List<ChatMessage> evicted = new ArrayList<>();
            evicted.add(buffer.remove(0));
            // 如果下一条是不同类型（user/ai 配对），一起挤出
            if (!buffer.isEmpty() && evicted.get(0).type() != buffer.get(0).type()) {
                evicted.add(buffer.remove(0));
            }
            allEvicted.addAll(evicted);
        }

        // 先用旧摘要快速持久化（不阻塞对话响应）
        persistMessages(currentSummary, buffer);

        // 异步处理被挤出的消息：提取画像 + 刷新摘要
        if (!allEvicted.isEmpty()) {
            String sessionId = String.valueOf(id);
            profileExtractor.updateProfileAsync(sessionId, allEvicted);

            final String oldSummary = currentSummary;
            summaryExecutor.submit(() -> {
                String newSummary = regenerateSummary(oldSummary, allEvicted);
                if (newSummary != null && !newSummary.isBlank()) {
                    applySummaryToStore(newSummary);
                }
            });
        }
    }

    /**
     * 异步摘要生成完成后，把新摘要写回 store。
     * synchronized 保证不会和 add() 并发写冲突。
     */
    private synchronized void applySummaryToStore(String newSummary) {
        try {
            List<ChatMessage> all = store.getMessages(id);
            List<ChatMessage> buffer = new ArrayList<>();
            for (ChatMessage msg : all) {
                if (!isSummaryMessage(msg)) {
                    buffer.add(msg);
                }
            }
            persistMessages(newSummary, buffer);
            log.info("状态摘要已异步写回 [session={}]: {}...", id,
                    newSummary.length() > 50 ? newSummary.substring(0, 50) : newSummary);
        } catch (Exception e) {
            log.error("异步写回摘要失败 [session={}]", id, e);
        }
    }

    /**
     * 持久化摘要 + 缓冲区到 store
     */
    private void persistMessages(String summary, List<ChatMessage> buffer) {
        List<ChatMessage> toStore = new ArrayList<>();
        if (summary != null && !summary.isBlank()) {
            toStore.add(dev.langchain4j.data.message.SystemMessage.from(
                    SUMMARY_PREFIX + summary));
        }
        toStore.addAll(buffer);
        store.updateMessages(id, toStore);
    }

    public void shutdown() {
        summaryExecutor.shutdown();
    }

    @Override
    public synchronized List<ChatMessage> messages() {
        return store.getMessages(id);
    }

    @Override
    public synchronized void clear() {
        store.deleteMessages(id);
        summaryExecutor.shutdownNow();
    }

    /**
     * 用被挤出的消息更新滚动摘要。新信息覆盖旧信息。
     */
    private String regenerateSummary(String oldSummary, List<ChatMessage> evicted) {
        try {
            String old = oldSummary != null ? oldSummary : "（无）";
            String conversation = formatMessages(evicted);
            String prompt = String.format(SUMMARY_PROMPT, old, conversation);

            log.info("生成状态摘要 [session={}]", id);
            String newSummary = model.generate(prompt);

            if (newSummary == null || newSummary.isBlank()) {
                log.warn("状态摘要生成失败 [session={}]", id);
                return oldSummary;
            }

            String result = newSummary.trim();
            log.info("状态摘要已更新 [session={}]: {}...", id,
                    result.length() > 50 ? result.substring(0, 50) : result);
            return result;
        } catch (Exception e) {
            log.error("生成状态摘要失败 [session={}]", id, e);
            return oldSummary;
        }
    }

    private boolean isSummaryMessage(ChatMessage msg) {
        if (msg instanceof dev.langchain4j.data.message.SystemMessage sm) {
            return sm.text() != null && sm.text().startsWith(SUMMARY_PREFIX);
        }
        return false;
    }

    private String extractSummaryText(ChatMessage msg) {
        if (msg instanceof dev.langchain4j.data.message.SystemMessage sm) {
            String text = sm.text();
            if (text != null && text.startsWith(SUMMARY_PREFIX)) {
                return text.substring(SUMMARY_PREFIX.length());
            }
        }
        return null;
    }

    private String formatMessages(List<ChatMessage> messages) {
        ConversationIdentityService.ConversationIdentity identity =
                identityService.forSession(String.valueOf(id));
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
