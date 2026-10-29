package com.demo.WechatBot;

import com.demo.WechatBot.service.AiChatService;
import com.demo.WechatBot.service.BotPersonaStore;
import com.demo.WechatBot.service.ImageService;
import com.demo.WechatBot.service.MultimodalService;
import com.demo.WechatBot.service.VideoService;
import com.demo.WechatBot.service.VoiceService;
import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.model.MessageItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MultimodalServicePersonaTest {

    private static final String SESSION = "bot:user";

    private VoiceService voiceService;
    private ImageService imageService;
    private AiChatService aiChatService;
    private BotPersonaStore personaStore;
    private MultimodalService multimodalService;

    @BeforeEach
    void setUp() {
        voiceService = mock(VoiceService.class);
        imageService = mock(ImageService.class);
        VideoService videoService = mock(VideoService.class);
        aiChatService = mock(AiChatService.class);
        personaStore = mock(BotPersonaStore.class);
        multimodalService = new MultimodalService(
                voiceService, imageService, videoService, aiChatService, personaStore);
    }

    @Test
    void voiceReplyUsesPersonaVoice() {
        when(voiceService.shouldReplyWithVoice("你好")).thenReturn(true);
        when(personaStore.getTtsVoice(SESSION)).thenReturn("male-voice");

        multimodalService.maybeSynthesizeVoice(SESSION, "你好");

        verify(voiceService).synthesizeVoice("你好", "male-voice");
    }

    @Test
    void imageMessageContinuesOnboardingBeforeRecognition() {
        when(aiChatService.isPersonaReady(SESSION)).thenReturn(false);
        when(aiChatService.chat(eq(SESSION), anyString())).thenReturn("先告诉我你的名字吧");

        String reply = multimodalService.handleImage(null, null, SESSION);

        assertEquals("先告诉我你的名字吧", reply);
        verifyNoInteractions(imageService);
    }

    @Test
    void recognizedImageReturnsToMainChatPersonaFlow() throws Exception {
        ILinkClient client = mock(ILinkClient.class);
        MessageItem item = mock(MessageItem.class);
        byte[] imageBytes = {1, 2, 3};
        when(aiChatService.isPersonaReady(SESSION)).thenReturn(true);
        when(client.downloadImageFromMessageItem(item)).thenReturn(imageBytes);
        when(imageService.describeImage(imageBytes, "image/jpeg"))
                .thenReturn("一只猫趴在桌上");
        when(aiChatService.chat(eq(SESSION), anyString())).thenReturn("它看起来好乖");

        String reply = multimodalService.handleImage(client, item, SESSION);

        assertEquals("它看起来好乖", reply);
        verify(aiChatService).chat(eq(SESSION), argThat(message -> message.contains("一只猫趴在桌上")));
    }
}
