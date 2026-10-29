package com.demo.WechatBot.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 主动消息调度器。
 * <p>
 * 每隔固定间隔检查一次，在满足条件时让 AI 主动给用户发消息，
 * 模拟"对方突然想起你"的行为。
 * </p>
 * <p>
 * 触发条件（全部满足才发）：
 * <ul>
 *   <li>机器人已登录运行</li>
 *   <li>当前时间在 8:00-23:00 之间（不打扰睡眠）</li>
 *   <li>随机概率命中（基础 25%，超过 4 小时未互动提高到 60%）</li>
 *   <li>有历史对话的用户存在</li>
 * </ul>
 * </p>
 */
@Component
public class ProactiveMessageScheduler {

    private static final Logger log = LoggerFactory.getLogger(ProactiveMessageScheduler.class);

    private final BotInstanceManager botManager;
    private final AiChatService aiChatService;
    private final MemoryStore memoryStore;
    private final RelationshipStateStore relationshipStateStore;

    public ProactiveMessageScheduler(BotInstanceManager botManager,
                                     AiChatService aiChatService,
                                     MemoryStore memoryStore,
                                     RelationshipStateStore relationshipStateStore) {
        this.botManager = botManager;
        this.aiChatService = aiChatService;
        this.memoryStore = memoryStore;
        this.relationshipStateStore = relationshipStateStore;
    }

    /**
     * 每 20 分钟检查一次，约 25% 概率发送，平均约 80 分钟一条。
     */
    @Scheduled(fixedDelay = 20 * 60 * 1000, initialDelay = 10 * 60 * 1000)
    public void maybeSendProactiveMessage() {
        int hour = LocalDateTime.now().getHour();
        if (hour < 8 || hour >= 23) {
            log.debug("当前时段不发送主动消息（hour={}）", hour);
            return;
        }

        List<String> sessions = memoryStore.findActiveSessions();
        if (sessions.isEmpty()) {
            log.debug("无活跃用户，跳过主动消息");
            return;
        }

        String sessionKey = sessions.get(
                ThreadLocalRandom.current().nextInt(sessions.size()));

        // 会话键格式为 botId:fromUserId；旧格式的历史数据无法路由，跳过
        int sep = sessionKey.indexOf(':');
        if (sep <= 0) {
            log.debug("旧格式会话键，跳过主动消息 [session={}]", sessionKey);
            return;
        }
        String botId = sessionKey.substring(0, sep);
        String userId = sessionKey.substring(sep + 1);

        if (!botManager.isOnline(botId)) {
            log.debug("机器人不在线，跳过主动消息 [bot={}]", botId);
            return;
        }

        // 根据互动间隔动态调整概率
        double probability = 0.25;
        long hoursSinceInteraction = getHoursSinceInteraction(sessionKey);
        if (hoursSinceInteraction >= 4) {
            probability = 0.60;
            log.debug("互动间隔 {} 小时，概率提高到 {}", hoursSinceInteraction, probability);
        }

        if (ThreadLocalRandom.current().nextDouble() > probability) {
            log.debug("本次未命中主动消息概率");
            return;
        }

        log.info("触发主动消息 [session={}]", sessionKey);
        String message = aiChatService.generateProactiveMessage(sessionKey);
        if (message != null && !message.isBlank()) {
            boolean sent = botManager.sendProactiveMessage(botId, userId, message);
            if (sent) {
                aiChatService.saveProactiveMessage(sessionKey, message);
            } else {
                log.info("主动消息发送失败，不写入对话记忆 [session={}]", sessionKey);
            }
        }
    }

    /**
     * 计算距离上次互动的小时数，无记录返回大值
     */
    private long getHoursSinceInteraction(String sessionId) {
        String lastInteraction = relationshipStateStore.getLastInteractionAt(sessionId);
        if (lastInteraction == null) {
            return Long.MAX_VALUE;
        }
        try {
            DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
            LocalDateTime lastTime = LocalDateTime.parse(lastInteraction, fmt);
            return java.time.Duration.between(lastTime, LocalDateTime.now()).toHours();
        } catch (Exception e) {
            log.warn("解析互动时间失败: {}", lastInteraction);
            return Long.MAX_VALUE;
        }
    }
}
