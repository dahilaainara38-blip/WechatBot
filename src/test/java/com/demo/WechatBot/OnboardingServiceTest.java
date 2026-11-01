package com.demo.WechatBot;

import com.demo.WechatBot.service.BotPersonaStore;
import com.demo.WechatBot.service.OnboardingService;
import dev.langchain4j.model.chat.ChatLanguageModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
                model, personaStore);
    }

    @Test
    void firstMessageOnlyReturnsSetupInstructions() {
        String reply = onboardingService.handle(SESSION, "你是谁", null);

        verify(personaStore).createOnboarding(SESSION);
        assertTrue(reply.contains("第一步"));
        assertTrue(reply.contains("你希望我叫什么名字"));
        verifyNoInteractions(model);
    }

    @Test
    void placeholderDoesNotAdvanceNameStep() {
        String reply = onboardingService.handle(SESSION, "...", onboarding(OnboardingService.STEP_NAME, "{}"));

        assertTrue(reply.contains("没有收到有效内容"));
        verify(personaStore, never()).saveOnboardingProgress(anyString(), anyString(), anyString());
        verifyNoInteractions(model);
    }

    @Test
    void chinesePunctuationDoesNotBecomeStyle() {
        String fields = "{\"名字\":\"阿哲\",\"性别\":\"男\",\"性格\":\"沉稳\"}";

        String reply = onboardingService.handle(
                SESSION, "，，", onboarding(OnboardingService.STEP_STYLE, fields));

        assertTrue(reply.contains("没有收到有效内容"));
        verify(personaStore, never()).savePending(anyString(), anyString(), anyString(), anyString());
        verifyNoInteractions(model);
    }

    @Test
    void delegatedPersonalityUsesExplicitDefault() {
        String reply = onboardingService.handle(
                SESSION, "随便", onboarding(OnboardingService.STEP_PERSONALITY, "{}"));

        verify(personaStore).saveOnboardingProgress(
                SESSION, OnboardingService.STEP_RELATIONSHIP,
                "{\"性格\":\"温柔、耐心、真诚\"}");
        assertTrue(reply.contains("关系"));
        verifyNoInteractions(model);
    }

    @Test
    void userAnswerIsSavedVerbatimAndAdvancesOneStep() {
        String reply = onboardingService.handle(SESSION, "阿哲", onboarding(OnboardingService.STEP_NAME, "{}"));

        verify(personaStore).saveOnboardingProgress(
                SESSION, OnboardingService.STEP_GENDER, "{\"名字\":\"阿哲\"}");
        assertTrue(reply.contains("性别"));
        verifyNoInteractions(model);
    }

    @Test
    void completedFieldsMoveToConfirmationWithoutSecondExtraction() {
        String fields = "{\"名字\":\"阿哲\",\"性别\":\"男\",\"性格\":\"沉稳\",\"关系\":\"朋友\"}";
        when(model.generate(anyString())).thenReturn("你是阿哲，性格沉稳，是用户的朋友，说话简短。");

        String reply = onboardingService.handle(
                SESSION, "说话简短", onboarding(OnboardingService.STEP_STYLE, fields));

        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(personaStore).saveOnboardingProgress(
                eq(SESSION), eq(OnboardingService.STEP_CONFIRM), jsonCaptor.capture());
        assertTrue(jsonCaptor.getValue().contains("\"风格\":\"说话简短\""));
        verify(personaStore).savePending(
                SESSION, "阿哲", "男",
                "你是阿哲，性格沉稳，是用户的朋友，说话简短。");
        assertTrue(reply.contains("我的名字：阿哲"));
        assertTrue(reply.contains("风格：说话简短"));
        assertTrue(reply.contains("确认"));
    }

    @Test
    void explicitConfirmationMarksPersonaReady() {
        when(model.generate(anyString())).thenReturn("嘿，我是阿哲");

        String reply = onboardingService.handle(SESSION, "确认", confirming("{}"));

        verify(personaStore).markReady(SESSION);
        assertEquals("嘿，我是阿哲", reply);
    }

    @Test
    void nameQuestionIsClarifiedWithoutRewritingPersona() {
        String reply = onboardingService.handle(
                SESSION, "我没有告诉你我叫林晚啊", confirming("{\"名字\":\"林晚\"}"));

        assertTrue(reply.contains("是我的名字，不是你的"));
        verify(personaStore, never()).savePending(anyString(), anyString(), anyString(), anyString());
        verifyNoInteractions(model);
    }

    @Test
    void invalidLegacyPlaceholderPersonaCannotBeConfirmed() {
        BotPersonaStore.Persona invalid = new BotPersonaStore.Persona(
                SESSION, BotPersonaStore.STATUS_CONFIRMING,
                "...", "...", "名字：...\n性别：...", "",
                OnboardingService.STEP_CONFIRM, "{}");

        String reply = onboardingService.handle(SESSION, "确认", invalid);

        verify(personaStore).resetToOnboarding(SESSION);
        verify(personaStore, never()).markReady(anyString());
        assertTrue(reply.contains("草稿不完整"));
        verifyNoInteractions(model);
    }

    @Test
    void explicitModificationReturnsToRequestedField() {
        String reply = onboardingService.handle(SESSION, "修改名字", confirming("{\"名字\":\"阿哲\"}"));

        verify(personaStore).saveOnboardingProgress(
                SESSION, OnboardingService.STEP_NAME, "{\"名字\":\"阿哲\",\"性别\":\"男\"}");
        assertTrue(reply.contains("你希望我叫什么名字"));
        verifyNoInteractions(model);
    }

    private BotPersonaStore.Persona onboarding(String step, String fields) {
        return new BotPersonaStore.Persona(
                SESSION, BotPersonaStore.STATUS_ONBOARDING,
                null, null, null, "", step, fields);
    }

    private BotPersonaStore.Persona confirming(String fields) {
        return new BotPersonaStore.Persona(
                SESSION, BotPersonaStore.STATUS_CONFIRMING,
                "阿哲", "男", "你是阿哲，性格沉稳。", "",
                OnboardingService.STEP_CONFIRM, fields);
    }
}
