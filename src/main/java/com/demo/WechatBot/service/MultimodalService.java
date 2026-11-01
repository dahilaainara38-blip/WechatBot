package com.demo.WechatBot.service;

import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.model.MessageItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** 图片消息编排：下载图片、识别内容，再交给主文本聊天服务回复。 */
@Service
public class MultimodalService {

    private static final Logger log = LoggerFactory.getLogger(MultimodalService.class);

    private final ImageService imageService;
    private final AiChatService aiChatService;

    public MultimodalService(ImageService imageService, AiChatService aiChatService) {
        this.imageService = imageService;
        this.aiChatService = aiChatService;
    }

    public String handleImage(ILinkClient client, MessageItem item, String sessionKey) {
        if (!aiChatService.isPersonaReady(sessionKey)) {
            return aiChatService.getOnboardingPrompt(sessionKey);
        }
        try {
            byte[] imageBytes = client.downloadImageFromMessageItem(item);
            String description = imageService.describeImage(imageBytes, "image/jpeg");
            return aiChatService.chat(
                    sessionKey, "（用户发来一张图片，图片内容描述：" + description + "）");
        } catch (Exception e) {
            log.error("处理图片消息失败 [session={}]", sessionKey, e);
            aiChatService.recordMediaFailure(sessionKey);
            return "这张图片我这边没识别出来，你重新发一下，或者描述给我听";
        }
    }
}
