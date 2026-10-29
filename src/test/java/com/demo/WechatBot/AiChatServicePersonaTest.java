package com.demo.WechatBot;

import com.demo.WechatBot.config.AiProperties;
import com.demo.WechatBot.config.DeepSeekProperties;
import com.demo.WechatBot.service.AiChatService;
import com.demo.WechatBot.service.BotPersonaStore;
import com.demo.WechatBot.service.ConversationIdentityService;
import com.demo.WechatBot.service.MemoryStore;
import com.demo.WechatBot.service.OnboardingService;
import com.demo.WechatBot.service.RelationshipStateExtractor;
import com.demo.WechatBot.service.RelationshipStateStore;
import com.demo.WechatBot.service.UserProfileStore;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.Resource;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AiChatServicePersonaTest {

    private static final String SESSION = "bot:user";

    private AiChatService aiChatService;
    private AiChatService.ChatAssistant assistant;
    private OnboardingService onboardingService;
    private RelationshipStateExtractor relationshipStateExtractor;
    private BotPersonaStore personaStore;
    private MemoryStore memoryStore;

    @BeforeEach
    void setUp() {
        DeepSeekProperties deepSeekProperties = new DeepSeekProperties();
        AiProperties aiProperties = new AiProperties();
        Resource promptResource = mock(Resource.class);
        memoryStore = mock(MemoryStore.class);
        ChatMemoryStore chatMemoryStore = mock(ChatMemoryStore.class);
        UserProfileStore userProfileStore = mock(UserProfileStore.class);
        RelationshipStateStore relationshipStateStore = mock(RelationshipStateStore.class);
        personaStore = mock(BotPersonaStore.class);
        ConversationIdentityService identityService = new ConversationIdentityService(personaStore);

        aiChatService = new AiChatService(
                deepSeekProperties, aiProperties, promptResource, memoryStore,
                chatMemoryStore, userProfileStore, relationshipStateStore, personaStore,
                identityService);

        assistant = mock(AiChatService.ChatAssistant.class);
        onboardingService = mock(OnboardingService.class);
        relationshipStateExtractor = mock(RelationshipStateExtractor.class);
        ReflectionTestUtils.setField(aiChatService, "assistant", assistant);
        ReflectionTestUtils.setField(aiChatService, "onboardingService", onboardingService);
        ReflectionTestUtils.setField(
                aiChatService, "relationshipStateExtractor", relationshipStateExtractor);
        ReflectionTestUtils.setField(aiChatService, "systemPrompt", "通用聊天规则");
    }

    @Test
    void newConversationUsesOnboardingInsteadOfNormalChat() {
        when(personaStore.find(SESSION)).thenReturn(null);
        when(onboardingService.handle(eq(SESSION), eq("你好"), isNull()))
                .thenReturn("你希望我叫什么名字？");

        String reply = aiChatService.chat(SESSION, "你好");

        assertEquals("你希望我叫什么名字？", reply);
        verifyNoInteractions(assistant);
        verify(memoryStore, never()).save(anyString(), anyString(), anyString());
    }

    @Test
    void readyPersonaIsInjectedIntoNormalChatPrompt() {
        BotPersonaStore.Persona ready = new BotPersonaStore.Persona(
                SESSION, BotPersonaStore.STATUS_READY,
                "阿哲", "男", "你是阿哲，性格沉稳。", "", "male-voice");
        when(personaStore.find(SESSION)).thenReturn(ready);
        when(personaStore.isReady(ready)).thenReturn(true);
        when(personaStore.getPersonaText(SESSION)).thenReturn("你是阿哲，性格沉稳。");
        when(assistant.chat(eq(SESSION), anyString(), eq("你好"))).thenReturn("我在");

        String reply = aiChatService.chat(SESSION, "你好");

        assertEquals("我在", reply);
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(assistant).chat(eq(SESSION), promptCaptor.capture(), eq("你好"));
        assertTrue(promptCaptor.getValue().contains("【你的身份与人设】"));
        assertTrue(promptCaptor.getValue().contains("你是阿哲，性格沉稳。"));
        assertTrue(promptCaptor.getValue().contains("通用聊天规则"));
        verify(memoryStore).save(SESSION, "user", "你好");
        verify(memoryStore).save(SESSION, "assistant", "我在");
        verify(relationshipStateExtractor).updateStateAsync(SESSION, "你好", "我在");
    }

    @Test
    void resetCommandReturnsToOnboarding() {
        String reply = aiChatService.chat(SESSION, "重置人设");

        assertEquals("好，那我们重新来。你希望我叫什么名字？", reply);
        var order = inOrder(personaStore);
        order.verify(personaStore).createOnboarding(SESSION);
        order.verify(personaStore).resetToOnboarding(SESSION);
        verifyNoInteractions(assistant);
    }

    @Test
    void proactiveMessageWaitsUntilPersonaIsReady() {
        when(personaStore.isReady(SESSION)).thenReturn(false);

        assertNull(aiChatService.generateProactiveMessage(SESSION));
        verifyNoInteractions(assistant);
    }
}
