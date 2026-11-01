package com.demo.WechatBot.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatLanguageModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 首次人设引导。
 *
 * 收集阶段由后端状态机逐步保存用户原话，不让模型猜测字段；只有资料收集完整后，
 * 才调用模型润色人设正文。用户明确确认前，会话一直保持未就绪状态。
 */
public class OnboardingService {

    private static final Logger log = LoggerFactory.getLogger(OnboardingService.class);

    public static final String STEP_NAME = "NAME";
    public static final String STEP_GENDER = "GENDER";
    public static final String STEP_PERSONALITY = "PERSONALITY";
    public static final String STEP_RELATIONSHIP = "RELATIONSHIP";
    public static final String STEP_STYLE = "STYLE";
    public static final String STEP_CONFIRM = "CONFIRM";

    private static final String FIELD_NAME = "名字";
    private static final String FIELD_GENDER = "性别";
    private static final String FIELD_PERSONALITY = "性格";
    private static final String FIELD_RELATIONSHIP = "关系";
    private static final String FIELD_STYLE = "风格";
    private static final String[] FIELD_LABELS = {
            FIELD_NAME, FIELD_GENDER, FIELD_PERSONALITY, FIELD_RELATIONSHIP, FIELD_STYLE
    };

    private static final String WELCOME = """
            欢迎来到陪伴聊天。开始前，我们先设置我的聊天人设。
            我会依次询问名字、性别、性格、关系和说话风格，确认后才开始正式聊天。
            第一步：你希望我叫什么名字？""";

    private static final Set<String> ACCEPT_REPLIES = Set.of(
            "可以", "确认", "就这样", "行", "挺好的", "满意", "开始", "开始聊天");
    private static final Set<String> DELEGATE_REPLIES = Set.of(
            "随便", "你定", "都可以", "看你", "你决定");
    private static final String DEFAULT_PERSONALITY = "温柔、耐心、真诚";

    private static final String GENERATE_PROMPT = """
            根据以下用户逐项确认的字段，为聊天伙伴写一张人设卡。

            【已确认字段】
            %s

            要求：
            - 用第二人称“你”来写，200字以内
            - 只能使用已确认字段中的信息，不得补充或猜测其他事实
            - 不得创造用户姓名、共同经历、现实身份或未提供的背景
            - 关系、风格或背景未提供时直接省略
            - 具体、自然，不要堆砌空泛形容词
            - 不要出现“AI”“人设”“设定”“角色扮演”这类词

            只输出人设卡正文，不要其他内容。
            """;

    private static final String GREETING_USER_MSG =
            "（对方刚刚确认了你的样子，你正式开始陪伴对方了。用你的身份自然地打个招呼，" +
            "表示你很期待以这个身份陪对方，一两句话就好。）";

    private final ChatLanguageModel model;
    private final BotPersonaStore personaStore;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public OnboardingService(ChatLanguageModel model, BotPersonaStore personaStore) {
        this.model = model;
        this.personaStore = personaStore;
    }

    /** 首次收到消息时只创建记录并展示说明，不消费用户的第一句话。 */
    public String start(String sessionKey) {
        personaStore.createOnboarding(sessionKey);
        return WELCOME;
    }

    /** 图片无法作为当前字段答案时，重复当前设置提示。 */
    public String currentPrompt(BotPersonaStore.Persona persona) {
        if (persona == null) {
            return WELCOME;
        }
        if (BotPersonaStore.STATUS_CONFIRMING.equals(persona.status())) {
            return buildConfirmSummary(readFields(persona));
        }
        return questionForStep(normalizeStep(persona.onboardingStep()));
    }

    public String handle(String sessionKey, String userMessage, BotPersonaStore.Persona persona) {
        if (persona == null) {
            return start(sessionKey);
        }

        String input = userMessage != null ? userMessage.trim() : "";
        if ("重新设置".equals(input) || "重置人设".equals(input)) {
            personaStore.resetToOnboarding(sessionKey);
            return WELCOME;
        }
        if (BotPersonaStore.STATUS_CONFIRMING.equals(persona.status())) {
            if (!hasValidPendingPersona(persona)) {
                personaStore.resetToOnboarding(sessionKey);
                return "之前的人设草稿不完整，已重新开始设置。\n" + WELCOME;
            }
            return handleConfirming(sessionKey, input, persona);
        }
        return handleOnboarding(sessionKey, input, persona);
    }

    private String handleOnboarding(String sessionKey, String input, BotPersonaStore.Persona persona) {
        String step = normalizeStep(persona.onboardingStep());
        Map<String, String> fields = readFields(persona);

        if ("上一步".equals(input)) {
            String previous = previousStep(step);
            if (previous == null) {
                return "现在已经是第一步了。" + questionForStep(step);
            }
            personaStore.saveOnboardingProgress(sessionKey, previous, writeFields(fields));
            return questionForStep(previous);
        }

        boolean optional = STEP_RELATIONSHIP.equals(step) || STEP_STYLE.equals(step);
        if (optional && ("跳过".equals(input) || DELEGATE_REPLIES.contains(input))) {
            fields.remove(fieldForStep(step));
        } else if (STEP_PERSONALITY.equals(step) && DELEGATE_REPLIES.contains(input)) {
            fields.put(fieldForStep(step), DEFAULT_PERSONALITY);
        } else {
            String validationError = validateInput(step, input);
            if (validationError != null) {
                return validationError + questionForStep(step);
            }
            fields.put(fieldForStep(step), input);
        }

        String next = nextStep(step);
        if (!STEP_CONFIRM.equals(next)) {
            personaStore.saveOnboardingProgress(sessionKey, next, writeFields(fields));
            return questionForStep(next);
        }

        String fieldsJson = writeFields(fields);
        String personaText = generatePersona(fields);
        personaStore.saveOnboardingProgress(sessionKey, STEP_CONFIRM, fieldsJson);
        personaStore.savePending(
                sessionKey, fields.get(FIELD_NAME), fields.get(FIELD_GENDER),
                personaText);
        return buildConfirmSummary(fields);
    }

    private String handleConfirming(String sessionKey, String input, BotPersonaStore.Persona persona) {
        if (ACCEPT_REPLIES.contains(input)) {
            personaStore.markReady(sessionKey);
            return generateGreeting(persona.personaText());
        }

        String modificationStep = modificationStep(input);
        if (modificationStep != null) {
            personaStore.saveOnboardingProgress(
                    sessionKey, modificationStep, writeFields(readFields(persona)));
            return questionForStep(modificationStep);
        }

        if (input.contains("名字") || input.contains("我叫")) {
            return "这里的“名字”是我的名字，不是你的；你的名字不会被自动填写。" +
                    "如果要改，请回复“修改名字”；确认无误请回复“确认”。";
        }
        return "人设还未生效，这句话没有被保存为设置内容。" +
                "请回复“确认”、“修改名字/性别/性格/关系/风格”或“重新设置”。";
    }

    private String validateInput(String step, String input) {
        if (input.isBlank() || isPlaceholder(input)) {
            return "还没有收到有效内容。";
        }
        int maxLength = switch (step) {
            case STEP_NAME, STEP_GENDER -> 20;
            case STEP_RELATIONSHIP -> 100;
            default -> 200;
        };
        if (input.length() > maxLength) {
            return "这一项请控制在 " + maxLength + " 字以内。";
        }
        return null;
    }

    private boolean isPlaceholder(String input) {
        return input.matches("[\\p{P}\\p{S}\\s]+");
    }

    private boolean hasValidPendingPersona(BotPersonaStore.Persona persona) {
        return isConcrete(persona.name()) && isConcrete(persona.gender())
                && isConcrete(persona.personaText());
    }

    private boolean isConcrete(String value) {
        return value != null && !value.isBlank() && !isPlaceholder(value.trim());
    }

    private String generatePersona(Map<String, String> fields) {
        String fieldsText = fieldsText(fields);
        try {
            String persona = model.generate(String.format(GENERATE_PROMPT, fieldsText));
            if (persona != null && !persona.isBlank()) {
                return persona.trim();
            }
        } catch (Exception e) {
            log.error("生成人设卡失败，使用已确认字段作为兜底", e);
        }
        return fieldsText;
    }

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

    private String buildConfirmSummary(Map<String, String> fields) {
        StringBuilder sb = new StringBuilder("根据你提供的信息，我的虚拟聊天人设是：");
        for (String label : FIELD_LABELS) {
            String value = fields.get(label);
            if (value != null && !value.isBlank()) {
                sb.append("\n").append(FIELD_NAME.equals(label) ? "我的名字" : label)
                        .append("：").append(value);
            }
        }
        sb.append("\n回复“确认”开始聊天；也可以回复“修改名字/性别/性格/关系/风格”或“重新设置”。");
        return sb.toString();
    }

    private Map<String, String> readFields(BotPersonaStore.Persona persona) {
        Map<String, String> fields = new LinkedHashMap<>();
        String json = persona.draftFields();
        if (json != null && !json.isBlank()) {
            try {
                Map<String, String> stored = objectMapper.readValue(
                        json, new TypeReference<LinkedHashMap<String, String>>() { });
                for (String label : FIELD_LABELS) {
                    String value = stored.get(label);
                    if (value != null && !value.isBlank()) {
                        fields.put(label, value);
                    }
                }
            } catch (JsonProcessingException e) {
                log.warn("人设草稿 JSON 无效，忽略损坏草稿 [session={}]", persona.sessionKey());
            }
        }
        // 兼容升级前已进入确认态的记录。
        putLegacyField(fields, FIELD_NAME, persona.name());
        putLegacyField(fields, FIELD_GENDER, persona.gender());
        return fields;
    }

    private void putLegacyField(Map<String, String> fields, String label, String value) {
        if (!fields.containsKey(label) && value != null && !value.isBlank() && !isPlaceholder(value)) {
            fields.put(label, value);
        }
    }

    private String writeFields(Map<String, String> fields) {
        try {
            return objectMapper.writeValueAsString(fields);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("无法保存人设草稿", e);
        }
    }

    private String fieldsText(Map<String, String> fields) {
        return String.join("\n", fields.entrySet().stream()
                .map(entry -> entry.getKey() + "：" + entry.getValue())
                .toList());
    }

    private String modificationStep(String input) {
        if (!(input.contains("修改") || input.contains("改") || input.contains("换")
                || input.contains("不对") || input.contains("错了"))) {
            return null;
        }
        if (input.contains(FIELD_NAME)) return STEP_NAME;
        if (input.contains(FIELD_GENDER)) return STEP_GENDER;
        if (input.contains(FIELD_PERSONALITY)) return STEP_PERSONALITY;
        if (input.contains(FIELD_RELATIONSHIP)) return STEP_RELATIONSHIP;
        if (input.contains(FIELD_STYLE)) return STEP_STYLE;
        return null;
    }

    private String normalizeStep(String step) {
        if (STEP_GENDER.equals(step) || STEP_PERSONALITY.equals(step)
                || STEP_RELATIONSHIP.equals(step) || STEP_STYLE.equals(step)) {
            return step;
        }
        return STEP_NAME;
    }

    private String nextStep(String step) {
        return switch (step) {
            case STEP_NAME -> STEP_GENDER;
            case STEP_GENDER -> STEP_PERSONALITY;
            case STEP_PERSONALITY -> STEP_RELATIONSHIP;
            case STEP_RELATIONSHIP -> STEP_STYLE;
            default -> STEP_CONFIRM;
        };
    }

    private String previousStep(String step) {
        return switch (step) {
            case STEP_GENDER -> STEP_NAME;
            case STEP_PERSONALITY -> STEP_GENDER;
            case STEP_RELATIONSHIP -> STEP_PERSONALITY;
            case STEP_STYLE -> STEP_RELATIONSHIP;
            default -> null;
        };
    }

    private String fieldForStep(String step) {
        return switch (step) {
            case STEP_NAME -> FIELD_NAME;
            case STEP_GENDER -> FIELD_GENDER;
            case STEP_PERSONALITY -> FIELD_PERSONALITY;
            case STEP_RELATIONSHIP -> FIELD_RELATIONSHIP;
            default -> FIELD_STYLE;
        };
    }

    private String questionForStep(String step) {
        return switch (step) {
            case STEP_GENDER -> "第二步：我的性别是什么？男生、女生或其他都可以。";
            case STEP_PERSONALITY -> "第三步：你希望我是什么性格？可以直接描述。";
            case STEP_RELATIONSHIP -> "第四步：我们是什么关系？不需要设置可以回复“跳过”。";
            case STEP_STYLE -> "第五步：你希望我怎么说话？不需要设置可以回复“跳过”。";
            default -> "你希望我叫什么名字？";
        };
    }

}
