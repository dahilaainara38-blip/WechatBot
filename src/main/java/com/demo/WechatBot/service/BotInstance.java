package com.demo.WechatBot.service;

import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.model.MessageItem;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * 单个微信机器人实例。
 * <p>
 * 包装一个 ILinkClient，负责该账号的消息接收、AI 回复与发送。
 * 消息处理使用实例级单线程执行器，保证同一机器人内消息串行不混乱，
 * 不同机器人之间互不阻塞。
 * </p>
 * <p>
 * 会话隔离：传给 AI 层的 sessionId 一律为 "botId:fromUserId" 复合键，
 * 避免同一用户与多个机器人聊天时记忆串线；发送消息时仍用原始 fromUserId。
 * </p>
 */
public class BotInstance {

    private static final Logger log = LoggerFactory.getLogger(BotInstance.class);

    private final String botId;
    private final ILinkClient client;
    private final AiChatService aiChatService;
    private final MultimodalService multimodalService;
    private final ConversationContextStore contextStore;
    private final Instant onlineSince = Instant.now();

    /**
     * 单线程执行器，保证本机器人消息按顺序串行处理
     */
    private final ExecutorService msgExecutor;

    public BotInstance(String botId, ILinkClient client,
                       AiChatService aiChatService, MultimodalService multimodalService,
                       ConversationContextStore contextStore) {
        this.botId = botId;
        this.client = client;
        this.aiChatService = aiChatService;
        this.multimodalService = multimodalService;
        this.contextStore = contextStore;
        this.msgExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "bot-msg-" + botId);
            t.setDaemon(true);
            return t;
        });
    }

    public String getBotId() {
        return botId;
    }

    public Instant getOnlineSince() {
        return onlineSince;
    }

    public ILinkClient getClient() {
        return client;
    }

    /**
     * 生成会话键：botId:fromUserId
     */
    public String sessionKey(String fromUser) {
        return botId + ":" + fromUser;
    }

    /**
     * 处理接收到的微信消息（由 SDK 消息监听回调触发）
     */
    public void handleMessages(List<WeixinMessage> messages) {
        for (WeixinMessage msg : messages) {
            String fromUser = msg.getFrom_user_id();
            if (fromUser != null && !fromUser.isBlank()) {
                contextStore.saveIncomingContext(
                        botId, fromUser, msg.getContext_token(),
                        msg.getMessage_id(), msg.getCreate_time_ms());
            }
            if (msg.getItem_list() == null) {
                log.debug("消息项列表为空 [bot={}, from={}]", botId, fromUser);
                continue;
            }

            for (MessageItem item : msg.getItem_list()) {
                // 1. 文本消息
                if (item.getType() == 1 && item.getText_item() != null) {
                    String text = item.getText_item().getText();
                    log.info("收到文本 [bot={}, from={}, length={}]", botId, fromUser,
                            text != null ? text.length() : 0);
                    if (text != null && !text.trim().isEmpty()) {
                        submitAiTask(fromUser, text);
                    }
                }
                // 2. 图片消息
                else if (item.getImage_item() != null) {
                    log.info("收到图片 [bot={}, from={}]", botId, fromUser);
                    submitMultimodalTask(fromUser, () ->
                            multimodalService.handleImage(client, item, sessionKey(fromUser)));
                }
            }
        }
    }

    /**
     * 导出消息拉取游标（停机时持久化用）
     */
    public String exportUpdatesCursor() {
        try {
            var rc = client.exportResumeContext();
            if (rc != null) {
                contextStore.saveSnapshots(botId, rc.getConversationContexts());
            }
            return rc != null ? rc.getUpdatesCursor() : null;
        } catch (Exception e) {
            log.warn("导出消息游标失败 [bot={}]", botId, e);
            return null;
        }
    }

    /**
     * 关闭实例：停止消息处理并释放 SDK 客户端资源
     */
    public void close() {
        msgExecutor.shutdown();
        try {
            client.close();
        } catch (Exception e) {
            log.warn("关闭客户端失败 [bot={}]", botId, e);
        }
    }

    /**
     * 将 AI 回复任务提交到本实例的执行器
     * <p>
     * 1. 模拟"看到消息"的自然停顿（1-3秒）
     * 2. 发送"正在输入"状态，异步等 AI 返回
     * 3. AI 返回后停止输入状态，按换行拆分回复逐条发送
     * 4. 每条消息间插入随机延迟（0.5-1.5秒），模拟逐条打字
     * </p>
     */
    private void submitAiTask(String fromUser, String text) {
        msgExecutor.submit(() -> {
            sleepRandom(1000, 3000);

            try {
                client.startTyping(fromUser);
            } catch (IOException e) {
                log.warn("发送输入状态失败 [bot={}, to={}]", botId, fromUser, e);
            }

            String reply;
            try {
                reply = aiChatService.chat(sessionKey(fromUser), text);
                log.info("AI回复已生成 [bot={}, to={}, length={}]", botId, fromUser,
                        reply != null ? reply.length() : 0);
            } catch (Exception e) {
                log.error("AI调用失败 [bot={}, to={}]", botId, fromUser, e);
                reply = "抱歉，我现在有点卡顿，请稍后再试。";
            }

            try {
                client.stopTyping(fromUser);
                sendReply(fromUser, reply, aiChatService.isPersonaReady(sessionKey(fromUser)));
            } catch (IOException e) {
                log.error("发送消息失败 [bot={}, to={}]", botId, fromUser, e);
            }
        });
    }

    /**
     * 将多模态处理任务提交到本实例的执行器
     */
    private void submitMultimodalTask(String fromUser, Supplier<String> task) {
        msgExecutor.submit(() -> {
            sleepRandom(1000, 3000);

            try {
                client.startTyping(fromUser);
            } catch (IOException e) {
                log.warn("发送输入状态失败 [bot={}, to={}]", botId, fromUser, e);
            }

            String reply;
            try {
                reply = task.get();
            } catch (Exception e) {
                log.error("多模态处理失败 [bot={}, from={}]", botId, fromUser, e);
                reply = "出了一点小问题，你再试一下";
            }

            try {
                client.stopTyping(fromUser);
                sendReply(fromUser, reply, aiChatService.isPersonaReady(sessionKey(fromUser)));
            } catch (IOException e) {
                log.error("发送消息失败 [bot={}, to={}]", botId, fromUser, e);
            }
        });
    }

    /** 统一的文本回复发送逻辑。 */
    private void sendReply(String toUser, String reply, boolean splitMessages) throws IOException {
        List<String> parts = splitMessages ? splitReply(reply) : List.of(reply);
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) {
                sleepRandom(500, 1500);
            }
            client.sendText(toUser, parts.get(i));
            log.info("消息已发送 [bot={}, to={}] ({}/{})", botId, toUser, i + 1, parts.size());
        }
    }

    /**
     * 将回复按换行拆分为多条消息，过滤空行
     */
    private List<String> splitReply(String reply) {
        List<String> parts = new ArrayList<>();
        for (String line : reply.split("\n")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                parts.add(trimmed);
            }
        }
        return parts.isEmpty() ? List.of(reply) : parts;
    }

    /**
     * 随机睡眠 [minMs, maxMs] 毫秒，模拟人类节奏
     */
    private void sleepRandom(int minMs, int maxMs) {
        try {
            Thread.sleep(ThreadLocalRandom.current().nextInt(minMs, maxMs));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
