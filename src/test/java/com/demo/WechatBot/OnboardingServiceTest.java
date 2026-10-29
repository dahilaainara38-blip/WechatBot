package com.demo.WechatBot;

import com.demo.WechatBot.service.BotPersonaStore;
import com.demo.WechatBot.service.OnboardingService;
import dev.langchain4j.model.chat.ChatLanguageModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OnboardingServiceTest {

    private static final String SESSION = "bot:user";

    private ChatLanguageModel model;
    private BotPersonaStore personaStore;
    private OnboardingService onboardingService;

    @BeforeEach
    void setUp() {
        model = mock(ChatLanguageModel.class);
        personaStore = mock(BotPersonaStore.class);
        onboardingService = new OnboardingService(
                model, personaStore, "female-voice", "male-voice");
    }

    @Test
    void collectsPersonaAndMovesToConfirmation() {
        BotPersonaStore.Persona onboarding = new BotPersonaStore.Persona(
                SESSION, BotPersonaStore.STATUS_ONBOARDING,
                null, null, null, "", null);
        when(personaStore.find(SESSION)).thenReturn(onboarding);
        when(model.generate(anyString())).thenReturn(
                "名字：阿哲\n性别：男\n性格：沉稳\n关系：朋友\n风格：简短",
                "你是阿哲，性格沉稳，是用户的朋友，说话简短。"
        );

        String reply = onboardingService.handle(
                SESSION, "叫阿哲，男生，性格沉稳，我们是朋友，说话简短", null);

        verify(personaStore).createOnboarding(SESSION);
        verify(personaStore).savePending(
                SESSION, "阿哲", "男",
                "你是阿哲，性格沉稳，是用户的朋友，说话简短。",
                "male-voice");
        assertTrue(reply.contains("名字：阿哲"));
        assertTrue(reply.contains("可以"));
    }

    @Test
    void confirmationMarksPersonaReady() {
        BotPersonaStore.Persona confirming = new BotPersonaStore.Persona(
                SESSION, BotPersonaStore.STATUS_CONFIRMING,
                "阿哲", "男", "你是阿哲，性格沉稳。", "", "male-voice");
        when(model.generate(anyString())).thenReturn("ACCEPT", "嘿，我是阿哲");

        String reply = onboardingService.handle(SESSION, "可以", confirming);

        verify(personaStore).markReady(SESSION);
        assertEquals("嘿，我是阿哲", reply);
    }

    @Test
    void modificationRegeneratesPersonaAndVoice() {
        BotPersonaStore.Persona confirming = new BotPersonaStore.Persona(
                SESSION, BotPersonaStore.STATUS_CONFIRMING,
                "阿哲", "男", "你是阿哲，性格沉稳。", "", "male-voice");
        when(model.generate(anyString())).thenReturn(
                "MODIFY: 改成温柔的女生",
                "你是阿哲，一个温柔的女生，说话简短。",
                "名字：阿哲\n性别：女\n性格：温柔\n风格：简短"
        );

        String reply = onboardingService.handle(SESSION, "改成温柔的女生", confirming);

        verify(personaStore).savePending(
                SESSION, "阿哲", "女",
                "你是阿哲，一个温柔的女生，说话简短。",
                "female-voice");
        assertTrue(reply.contains("性别：女"));
    }
}
