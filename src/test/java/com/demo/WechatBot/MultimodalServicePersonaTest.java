package com.demo.WechatBot;

import com.demo.WechatBot.service.AiChatService;
import com.demo.WechatBot.service.ImageService;
import com.demo.WechatBot.service.MultimodalService;
import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.model.MessageItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MultimodalServicePersonaTest {

    private static final String SESSION = "bot:user";

    private ImageService imageService;
    private AiChatService aiChatService;
    private MultimodalService multimodalService;

    @BeforeEach
    void setUp() {
        imageService = mock(ImageService.class);
        aiChatService = mock(AiChatService.class);
        multimodalService = new MultimodalService(imageService, aiChatService);
    }

    @Test
    void imageMessageContinuesOnboardingBeforeRecognition() {
        when(aiChatService.isPersonaReady(SESSION)).thenReturn(false);
        when(aiChatService.getOnboardingPrompt(SESSION)).thenReturn("先告诉我你的名字吧");

        String reply = multimodalService.handleImage(null, null, SESSION);

        assertEquals("先告诉我你的名字吧", reply);
        verify(aiChatService).getOnboardingPrompt(SESSION);
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

    @Test
    void failedImageRecognitionDoesNotEnterMainChat() throws Exception {
        ILinkClient client = mock(ILinkClient.class);
        MessageItem item = mock(MessageItem.class);
        byte[] imageBytes = {1, 2, 3};
        when(aiChatService.isPersonaReady(SESSION)).thenReturn(true);
        when(client.downloadImageFromMessageItem(item)).thenReturn(imageBytes);
        when(imageService.describeImage(imageBytes, "image/jpeg"))
                .thenThrow(new IllegalStateException("Model not exist"));

        String reply = multimodalService.handleImage(client, item, SESSION);

        assertEquals("这张图片我这边没识别出来，你重新发一下，或者描述给我听", reply);
        verify(aiChatService).recordMediaFailure(SESSION);
        verify(aiChatService, never()).chat(anyString(), anyString());
    }
}
