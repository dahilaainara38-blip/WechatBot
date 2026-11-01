package com.demo.WechatBot.service;

import com.demo.WechatBot.config.AiProperties;
import com.demo.WechatBot.config.DeepSeekProperties;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AI 聊天服务
 * <p>
 * 基于 LangChain4j AiServices + DeepSeek（OpenAI 兼容协议），
 * 每个微信用户独立会话（20 条消息滑动窗口）。
 * </p>
 */
@Service
public class AiChatService {

    private static final Logger log = LoggerFactory.getLogger(AiChatService.class);

	/**
	 * LangChain4j AI 助手接口
	 */
	public interface ChatAssistant {
		@SystemMessage("{{systemPrompt}}")
		String chat(@MemoryId String sessionId, @V("systemPrompt") String systemPrompt, @UserMessage String userMessage);
	}

    private final DeepSeekProperties deepSeekProperties;
    private final AiProperties aiProperties;
    private final Resource promptResource;
    private String systemPrompt;
    private final MemoryStore memoryStore;
    private final ChatMemoryStore chatMemoryStore;
    private final UserProfileStore userProfileStore;
    private final RelationshipStateStore relationshipStateStore;
    private final BotPersonaStore personaStore;
    private final ConversationIdentityService identityService;
    private ProfileExtractor profileExtractor;
    private RelationshipStateExtractor relationshipStateExtractor;
    private OnboardingService onboardingService;
    private ChatLanguageModel model;
    private ChatAssistant assistant;
    private final Map<Object, StatefulChatMemory> chatMemories = new ConcurrentHashMap<>();

    public AiChatService(DeepSeekProperties deepSeekProperties,
                         AiProperties aiProperties,
                         @org.springframework.beans.factory.annotation.Value("classpath:prompts/system-prompt.md") Resource promptResource,
                       MemoryStore memoryStore,
                     ChatMemoryStore chatMemoryStore,
                     UserProfileStore userProfileStore,
                     RelationshipStateStore relationshipStateStore,
                     BotPersonaStore personaStore,
                     ConversationIdentityService identityService) {
        this.deepSeekProperties = deepSeekProperties;
        this.aiProperties = aiProperties;
        this.promptResource = promptResource;
        this.memoryStore = memoryStore;
        this.chatMemoryStore = chatMemoryStore;
        this.userProfileStore = userProfileStore;
        this.relationshipStateStore = relationshipStateStore;
        this.personaStore = personaStore;
        this.identityService = identityService;
    }

    @PostConstruct
    public void init() throws IOException {
        this.systemPrompt = new String(
                promptResource.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8).trim();

        this.model = OpenAiChatModel.builder()
                .baseUrl(deepSeekProperties.getBaseUrl())
                .apiKey(deepSeekProperties.getApiKey())
                .modelName(deepSeekProperties.getModelName())
                .timeout(Duration.ofSeconds(deepSeekProperties.getTimeoutSeconds()))
                .maxRetries(deepSeekProperties.getMaxRetries())
                .build();

        this.profileExtractor = new ProfileExtractor(model, userProfileStore, identityService);

        this.relationshipStateExtractor = new RelationshipStateExtractor(
                model, relationshipStateStore, identityService);

        this.onboardingService = new OnboardingService(
                model, personaStore);

        this.assistant = AiServices.builder(ChatAssistant.class)
                .chatLanguageModel(model)
                .chatMemoryProvider(memoryId ->
                        chatMemories.computeIfAbsent(memoryId, id -> new StatefulChatMemory(
                                id, chatMemoryStore, model, profileExtractor, identityService, 16)))
                .build();

        log.info("AiChatService initialized with model: {} ({})",
                deepSeekProperties.getModelName(), deepSeekProperties.getBaseUrl());
    }

    /**
     * 发送消息给 AI，返回回复内容。
     *
     * @param sessionId 会话 ID（微信用户 from_user_id）
     * @param message   用户消息
     * @return AI 回复文本
     */
    public String chat(String sessionId, String message) {
        log.debug("AI chat request [session={}]: {}", sessionId, message);
		try {
            if (isResetPersonaCommand(message)) {
                personaStore.createOnboarding(sessionId);
                personaStore.resetToOnboarding(sessionId);
                return "好，那我们重新来。你希望我叫什么名字？";
            }

            BotPersonaStore.Persona persona = personaStore.find(sessionId);
            if (persona == null) {
                return onboardingService.start(sessionId);
            }
            if (!personaStore.isReady(persona)) {
                return onboardingService.handle(sessionId, message, persona);
            }

			String reply = assistant.chat(sessionId, buildDynamicPrompt(sessionId), message);
			// 持久化到 SQLite
			memoryStore.save(sessionId, "user", message);
			memoryStore.save(sessionId, "assistant", reply);
			// 异步更新关系状态
			relationshipStateExtractor.updateStateAsync(sessionId, message, reply);
			log.debug("AI chat response [session={}]: {}", sessionId, reply);
			return reply;
        } catch (Exception e) {
            log.error("AI chat error [session={}]", sessionId, e);
           return "抱歉，我现在有点卡顿，请稍后再试。";
       }
   }

    /**
     * 多模态消息在进入识别前用它判断是否需要先完成人设引导。
     */
    public boolean isPersonaReady(String sessionId) {
        return personaStore.isReady(sessionId);
    }

    /** 非文本消息无法回答当前设置问题时，只返回当前引导提示。 */
    public String getOnboardingPrompt(String sessionId) {
        BotPersonaStore.Persona persona = personaStore.find(sessionId);
        return persona == null
                ? onboardingService.start(sessionId)
                : onboardingService.currentPrompt(persona);
    }

    private boolean isResetPersonaCommand(String message) {
        if (message == null) return false;
        String command = message.trim();
        return "重置人设".equals(command)
                || "/重置人设".equals(command)
                || "/reset-persona".equalsIgnoreCase(command);
    }

    /**
     * 保存多模态消息到对话记忆（不经过 AI 生成，直接写入）。
     * 用于图片/视频等非文本消息的上下文记录，让后续对话能接上。
     *
     * @param sessionId 会话 ID
     * @param role      角色（"user" 或 "assistant"）
     * @param content   内容描述
     */
    public void saveMultimodalContext(String sessionId, String role, String content) {
        memoryStore.save(sessionId, role, content);
    }

    /** 将失败事实放入会话窗口，避免下一轮假装媒体已经成功识别。 */
    public void recordMediaFailure(String sessionId) {
        String note = "【媒体处理结果】上一张图片识别失败，没有可见内容，不得声称已经看过。";
        StatefulChatMemory memory = chatMemories.computeIfAbsent(sessionId, id ->
                new StatefulChatMemory(id, chatMemoryStore, model, profileExtractor, identityService, 16));
        memory.add(dev.langchain4j.data.message.SystemMessage.from(note));
        memoryStore.save(sessionId, "system", note);
    }

    /** 删除机器人数据时同步释放对应的内存实例和后台任务。 */
    public void clearBotMemory(String botId) {
        String prefix = botId + ":";
        for (Object id : chatMemories.keySet()) {
            if (!String.valueOf(id).startsWith(prefix)) continue;
            StatefulChatMemory memory = chatMemories.remove(id);
            if (memory != null) memory.clear();
        }
    }

    @PreDestroy
    public void shutdown() {
        chatMemories.values().forEach(StatefulChatMemory::shutdown);
        chatMemories.clear();
        if (profileExtractor != null) profileExtractor.shutdown();
        if (relationshipStateExtractor != null) relationshipStateExtractor.shutdown();
    }

    /**
     * 构建带当前时间的动态 system prompt。
     * 每次对话时拼接当前日期、星期、时间段，让 AI 有时间感知。
     */
    private String buildDynamicPrompt(String sessionId) {
        LocalDateTime now = LocalDateTime.now();
        String dateStr = now.format(DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm"));

        String[] weekDays = {"一", "二", "三", "四", "五", "六", "日"};
        String weekDay = "星期" + weekDays[now.getDayOfWeek().getValue() - 1];

        int hour = now.getHour();
        String period;
        if (hour < 5)       period = "深夜";
        else if (hour < 8)  period = "清晨";
        else if (hour < 11) period = "上午";
        else if (hour < 13) period = "中午";
        else if (hour < 18) period = "下午";
        else if (hour < 22) period = "晚上";
        else                period = "深夜";

        StringBuilder prompt = new StringBuilder();

        // 人设放在通用规则之前，确保身份、关系和说话风格对本轮对话生效
        ConversationIdentityService.ConversationIdentity identity = identityService.forSession(sessionId);
        if (identity.personaText() != null && !identity.personaText().isBlank()) {
            prompt.append("【你的身份与人设】\n").append(identity.personaText()).append("\n\n");
        }
        prompt.append(systemPrompt);

        // 注入用户画像
        String profile = userProfileStore.getProfile(sessionId);
        if (profile != null && !profile.isBlank()) {
            prompt.append("\n\n【关于用户的信息】\n").append(profile);
        }

        // 注入关系状态（含行为约束翻译）
        String state = relationshipStateStore.getState(sessionId);
        if (state != null && !state.isBlank()) {
            prompt.append("\n\n【当前关系状态】\n").append(state);
            prompt.append("\n").append(translateStateToBehavior(state));
        }

        // 注入重逢时刻行为指令
        String lastInteraction = relationshipStateStore.getLastInteractionAt(sessionId);
        if (lastInteraction != null) {
            String gap = formatInteractionGap(lastInteraction);
            String reunion = buildReunionDirective(lastInteraction);
            prompt.append("\n\n【距离上次互动】\n").append(gap);
            if (reunion != null) {
                prompt.append("\n").append(reunion);
            }
        }

        // 注入当前时间
        prompt.append("\n\n【当前时间】\n")
                .append(dateStr).append(" ").append(weekDay)
                .append("（").append(period).append("）")
                .append("\n请根据当前时间自然地聊天。饭点关心对方吃了没，深夜提醒对方休息。");

        return prompt.toString();
    }

    /**
     * 将关系状态翻译成具体行为约束，让模型不只是"看到"状态而是"照做"。
     * 通过关键词匹配状态文本，输出行为指令。
     */
    private String translateStateToBehavior(String state) {
        StringBuilder behavior = new StringBuilder("【行为指引】");
        String lower = state.toLowerCase();

        if (lower.contains("冷淡")) {
            behavior.append("你现在不太想理对方。回复要短，一两个字就行，不要主动找话题，对方说什么都淡淡的。");
        } else if (lower.contains("微凉")) {
            behavior.append("你现在有点小情绪。回复比平时短，可以带点冷淡，不要太热情。");
        } else if (lower.contains("甜蜜")) {
            behavior.append("你现在心情很好。可以撒娇，主动找话题，语气甜一点。");
        } else if (lower.contains("温暖")) {
            behavior.append("你现在心情不错，自然地聊就好，可以比平时多关心一句。");
        } else {
            behavior.append("正常聊天，保持你平时的语气。");
        }

        if (lower.contains("委屈") || lower.contains("小脾气")) {
            behavior.append("你现在有点委屈，可以带点小脾气，但不要真生气。");
        }

        return behavior.toString();
    }

    /**
     * 根据距离上次互动的时间，生成重逢行为指令。
     * 短间隔不加指令（正常接话），长间隔加重逢感指令。
     */
    private String buildReunionDirective(String lastInteractionAt) {
        try {
            DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
            LocalDateTime lastTime = LocalDateTime.parse(lastInteractionAt, fmt);
            long minutes = java.time.Duration.between(lastTime, LocalDateTime.now()).toMinutes();
            long hours = minutes / 60;

            if (minutes < 5) {
                return null; // 连续聊天，不加特殊指令
            } else if (hours < 2) {
                return null; // 短暂间隔，自然接着聊
            } else if (hours < 8) {
                return "【重逢语气】你们有一阵没聊了，开场带点重逢感。"
                        + "比如主动说一件刚才发生的事，或者问对方刚才在干嘛。不要生硬地问'在吗'。";
            } else {
                // 超过8小时，判断是否跨天
                LocalDate lastDate = lastTime.toLocalDate();
                LocalDate today = LocalDate.now();
                if (lastDate.isBefore(today)) {
                    int h = LocalDateTime.now().getHour();
                    if (h < 12) {
                        return "【重逢语气】你们隔了一夜才联系。用早安的语气，"
                                + "可以问对方昨晚几点睡的、睡得好不好。自然一点，不要像闹钟。";
                    } else {
                        return "【重逢语气】你们隔了很久才联系，而且是跨天了。"
                                + "可以带点小委屈，问对方今天怎么这么晚才找你。";
                    }
                } else {
                    return "【重逢语气】你们今天还没怎么聊过。"
                            + "可以自然地找对方说话，问问对方今天怎么样了。";
                }
            }
        } catch (Exception e) {
            log.warn("构建重逢指令失败: {}", lastInteractionAt);
            return null;
        }
    }

    /**
     * 计算距离上次互动的时间间隔，返回可读文本
     */
    private String formatInteractionGap(String lastInteractionAt) {
        try {
            DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
            LocalDateTime lastTime = LocalDateTime.parse(lastInteractionAt, fmt);
            long minutes = java.time.Duration.between(lastTime, LocalDateTime.now()).toMinutes();
            if (minutes < 1) return "刚刚";
            if (minutes < 60) return minutes + " 分钟前";
            long hours = minutes / 60;
            if (hours < 24) return hours + " 小时前";
            long days = hours / 24;
            return days + " 天前";
        } catch (Exception e) {
            log.warn("解析互动时间失败: {}", lastInteractionAt, e);
            return "未知";
        }
    }

}
