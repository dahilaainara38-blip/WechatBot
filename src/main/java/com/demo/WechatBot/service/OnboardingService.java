package com.demo.WechatBot.service;

import dev.langchain4j.model.chat.ChatLanguageModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 人设引导引擎。
 * <p>
 * 用户首次会话（或重置人设）后进入引导流程，通过对话收集人设要素，
 * 生成人设卡并让用户确认后才生效。状态机由 BotPersonaStore 持久化：
 * ONBOARDING（收集信息）→ CONFIRMING（出示摘要等确认）→ READY（生效）。
 * </p>
 * <p>
 * 引导态下的所有消息都在这里处理，不进入正常对话链路（强制完成人设）。
 * 引导对话以草稿形式持久化，重启后可以续接。
 * </p>
 */
public class OnboardingService {

    private static final Logger log = LoggerFactory.getLogger(OnboardingService.class);

    /** 人设要素标签（前三个为必需） */
    private static final String[] FIELD_LABELS = {"名字", "性别", "性格", "关系", "风格"};
    private static final String[] REQUIRED_LABELS = {"名字", "性别", "性格"};

    private static final String GUIDE_PROMPT = """
            你是一个刚被唤醒的聊天伙伴，目前还没有名字和性格——和你聊天的用户将决定你是谁。
            你的任务是通过轻松自然的对话，引导用户告诉你：
            1. 你的名字（对方希望你叫什么）
            2. 你的性别（男生/女生/其他都可以）
            3. 你的性格（比如温柔甜妹、高冷御姐、阳光大男孩、黏人奶狗，或任何对方描述的样子）
            4. 你们的关系（朋友/恋人/损友……）
            5. 说话风格（可选：话多话少、爱不爱用表情、口癖等）

            规则：
            - 一次最多问一两个问题，不要像查户口一样连珠炮
            - 用户聊别的可以简单接一句，但要自然地把话题引回"我是谁"的设定
            - 语气轻松，像一张等待被涂色的白纸，有点好奇有点期待
            - 每条回复控制在两行以内，微信聊天的感觉
            - 不要说"作为AI""人工智能助手"这种话，不要客服腔

            【到目前为止的引导对话】
            %s
            """;

    private static final String EXTRACT_PROMPT = """
            从以下引导对话中，提取用户为聊天伙伴确定的设定。

            【引导对话】
            %s

            输出格式（每行一条，"标签：内容"）：
            名字：...
            性别：...
            性格：...
            关系：...
            风格：...

            规则：
            - 只输出对话中明确出现的信息，没有的标签整行省略
            - 不要猜测、不要发挥
            - 只输出上述格式的内容
            """;

    private static final String GENERATE_PROMPT = """
            根据以下引导对话，为聊天伙伴写一张人设卡。

            【引导对话】
            %s

            【已提取的设定】
            %s

            人设卡要求：
            - 用第二人称"你"来写，200字以内
            - 包含：你是谁（名字/性别/性格）、你和用户的关系、你说话的风格、你们之间的小背景
            - 背景可以根据对话合理发挥一点，让关系有来由、有细节，但不要脱离用户描述的方向
            - 具体、生活化，不要空泛的形容词堆砌
            - 不要出现"AI""人设""设定""角色扮演"这类词

            只输出人设卡正文，不要其他内容。
            """;

    private static final String CONFIRM_CLASSIFY_PROMPT = """
            用户刚刚看到了你（聊天伙伴）的人设摘要，正在回复你。判断用户的意思：

            【用户的话】
            %s

            - 如果用户表示满意/同意/可以/就这样/行/挺好的，输出：ACCEPT
            - 如果用户想修改或补充任何内容，输出：MODIFY: 用户想修改的具体内容

            只输出一行。
            """;

    private static final String REVISE_PROMPT = """
            这是聊天伙伴当前的人设卡：

            【当前人设】
            %s

            用户想修改：%s

            请输出修改后的完整人设卡（200字以内，第二人称"你"，要求同前）。
            只输出人设卡正文。
            """;

    private static final String GREETING_USER_MSG =
            "（对方刚刚确认了你的样子，你正式开始陪伴对方了。用你的身份自然地打个招呼，" +
            "表示你很期待以这个身份陪对方，一两句话就好。）";

    private final ChatLanguageModel model;
    private final BotPersonaStore personaStore;
    private final String ttsVoiceFemale;
    private final String ttsVoiceMale;

    public OnboardingService(ChatLanguageModel model, BotPersonaStore personaStore,
                             String ttsVoiceFemale, String ttsVoiceMale) {
        this.model = model;
        this.personaStore = personaStore;
        this.ttsVoiceFemale = ttsVoiceFemale;
        this.ttsVoiceMale = ttsVoiceMale;
    }

    /**
     * 处理引导态消息，返回给用户的回复。
     *
     * @param sessionKey  会话键
     * @param userMessage 用户消息
     * @param persona     当前人设记录（null 表示首次会话）
     */
    public String handle(String sessionKey, String userMessage, BotPersonaStore.Persona persona) {
        if (persona == null) {
            personaStore.createOnboarding(sessionKey);
            persona = personaStore.find(sessionKey);
        }

        if (BotPersonaStore.STATUS_CONFIRMING.equals(persona.status())) {
            return handleConfirming(sessionKey, userMessage, persona);
        }
        return handleOnboarding(sessionKey, userMessage, persona);
    }

    /**
     * ONBOARDING：累积草稿 -> 提取字段 -> 齐了则生成人设卡进入确认，否则继续引导
     */
    private String handleOnboarding(String sessionKey, String userMessage, BotPersonaStore.Persona persona) {
        String draft = appendDraft(persona.draftContext(), "用户", userMessage);

        Map<String, String> fields = extractFields(draft);
        if (isComplete(fields)) {
            log.info("人设要素已收集齐 [session={}]: {}", sessionKey, fields);
            String personaText = generatePersona(draft, fields);
            String name = fields.get("名字");
            String gender = fields.get("性别");
            personaStore.savePending(sessionKey, name, gender, personaText, resolveVoice(gender));
            return buildConfirmSummary(fields);
        }

        // 信息不足，继续引导
        String reply = model.generate(String.format(GUIDE_PROMPT, draft));
        reply = reply != null ? reply.trim() : "";
        if (reply.isBlank()) {
            reply = "你希望我叫什么名字呀？";
        }
        personaStore.updateDraft(sessionKey, appendDraft(draft, "我", reply));
        return reply;
    }

    /**
     * CONFIRMING：判断用户意图，接受则生效，修改则重新生成
     */
    private String handleConfirming(String sessionKey, String userMessage, BotPersonaStore.Persona persona) {
        String verdict = model.generate(String.format(CONFIRM_CLASSIFY_PROMPT, userMessage));
        verdict = verdict != null ? verdict.trim() : "";
        log.info("人设确认结果 [session={}]: {}", sessionKey, verdict);

        if (verdict.startsWith("ACCEPT")) {
            personaStore.markReady(sessionKey);
            return generateGreeting(persona.personaText());
        }

        // MODIFY 或无法识别时按修改意见处理，保持在确认态
        String modification = verdict.startsWith("MODIFY")
                ? verdict.substring(verdict.indexOf(':') + 1).trim()
                : userMessage;
        if (modification.isBlank()) {
            return "想改哪里呀？直接告诉我就行";
        }
        String revised = model.generate(String.format(REVISE_PROMPT, persona.personaText(), modification));
        if (revised != null && !revised.isBlank()) {
            // 修改后重新提取关键字段，保持 name/gender/voice 与人设卡一致
            Map<String, String> fields = extractFields(
                    "用户对人设的要求：" + modification + "\n修改后的人设：\n" + revised);
            String name = fields.getOrDefault("名字", persona.name());
            String gender = fields.getOrDefault("性别", persona.gender());
            personaStore.savePending(sessionKey, name, gender, revised.trim(), resolveVoice(gender));
            return buildConfirmSummary(fields);
        }
        return "刚才没太跟上，你想改哪部分？";
    }

    /**
     * 从引导对话中提取人设字段
     */
    private Map<String, String> extractFields(String draft) {
        Map<String, String> fields = new LinkedHashMap<>();
        try {
            String output = model.generate(String.format(EXTRACT_PROMPT, draft));
            if (output == null) return fields;
            for (String line : output.split("\n")) {
                int idx = line.indexOf('：');
                if (idx <= 0) idx = line.indexOf(':');
                if (idx <= 0) continue;
                String label = line.substring(0, idx).trim().replace("*", "").replace(" ", "");
                String value = line.substring(idx + 1).trim();
                for (String known : FIELD_LABELS) {
                    if (label.contains(known) && !value.isBlank()) {
                        fields.put(known, value);
                    }
                }
            }
        } catch (Exception e) {
            log.error("人设字段提取失败", e);
        }
        return fields;
    }

    private boolean isComplete(Map<String, String> fields) {
        for (String label : REQUIRED_LABELS) {
            if (!fields.containsKey(label)) return false;
        }
        return true;
    }

    private String generatePersona(String draft, Map<String, String> fields) {
        String fieldsText = String.join("\n",
                fields.entrySet().stream().map(e -> e.getKey() + "：" + e.getValue()).toList());
        String persona = model.generate(String.format(GENERATE_PROMPT, draft, fieldsText));
        if (persona == null || persona.isBlank()) {
            // 兜底：用字段拼一张简版人设卡
            persona = fieldsText;
        }
        return persona.trim();
    }

    /**
     * 用户确认后，以新身份生成第一句问候
     */
    private String generateGreeting(String personaText) {
        try {
            String greeting = model.generate(personaText + "\n\n" + GREETING_USER_MSG);
            if (greeting != null && !greeting.isBlank()) {
                return greeting.trim();
            }
        } catch (Exception e) {
            log.error("生成人设问候失败", e);
        }
        return "好啦，从现在起我就正式陪着你啦";
    }

    /**
     * 用字段拼确认摘要（模板生成，保证与人设卡一致）
     */
    private String buildConfirmSummary(Map<String, String> fields) {
        StringBuilder sb = new StringBuilder("我给自己整理了一份样子，你看看：");
        for (String label : FIELD_LABELS) {
            String value = fields.get(label);
            if (value != null) {
                sb.append("\n").append(label).append("：").append(value);
            }
        }
        sb.append("\n满意的话回我一句\"可以\"，我就正式开始啦。想改哪里直接告诉我。");
        return sb.toString();
    }

    /**
     * 根据人设性别选择 TTS 音色
     */
    private String resolveVoice(String gender) {
        if (gender != null && gender.contains("男")) {
            return ttsVoiceMale;
        }
        return ttsVoiceFemale;
    }

    private String appendDraft(String draft, String role, String content) {
        StringBuilder sb = new StringBuilder(draft != null ? draft : "");
        if (sb.length() > 0) sb.append("\n");
        sb.append(role).append("：").append(content);
        // 草稿上限 4000 字，超出截掉头部（保留最近的对话）
        if (sb.length() > 4000) {
            sb.delete(0, sb.length() - 4000);
        }
        return sb.toString();
    }
}
