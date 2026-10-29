package com.demo.WechatBot.service;

import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.model.MessageItem;
import com.github.wechat.ilink.sdk.core.model.VoiceItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;

/**
 * 多模态编排中枢。
 * <p>
 * 统一管理图片、语音、视频消息的接收处理和回复策略，
 * 串联各子服务（VoiceService、ImageService、VideoService）与 AiChatService。
 * </p>
 */
@Service
public class MultimodalService {

    private static final Logger log = LoggerFactory.getLogger(MultimodalService.class);

    private final VoiceService voiceService;
    private final ImageService imageService;
    private final VideoService videoService;
    private final AiChatService aiChatService;
    private final BotPersonaStore personaStore;

    public MultimodalService(VoiceService voiceService,
                             ImageService imageService,
                             VideoService videoService,
                             AiChatService aiChatService,
                             BotPersonaStore personaStore) {
        this.voiceService = voiceService;
        this.imageService = imageService;
        this.videoService = videoService;
        this.aiChatService = aiChatService;
        this.personaStore = personaStore;
    }

    /**
     * 处理图片消息：下载图片 -> 生成中性描述 -> 由主聊天服务按人设回复
     *
     * @param client   ILinkClient 实例
     * @param item     包含图片的消息项
     * @param sessionKey 会话键（botId:fromUserId），用于对话记忆隔离
     * @return 回复文字
     */
    public String handleImage(ILinkClient client, MessageItem item, String sessionKey) {
        if (!aiChatService.isPersonaReady(sessionKey)) {
            return aiChatService.chat(sessionKey, "（对方发来一张图片，先继续完成聊天伙伴的初始设置）");
        }
        try {
            byte[] imageBytes = client.downloadImageFromMessageItem(item);
            String description = imageService.describeImage(imageBytes, "image/jpeg");
            return aiChatService.chat(
                    sessionKey, "（用户发来一张图片，图片内容描述：" + description + "）");
        } catch (IOException e) {
            log.error("处理图片消息失败 [session={}]", sessionKey, e);
            return "图片加载不出来，网络又卡了";
        }
    }

    /**
     * 处理语音消息：识别语音文字 -> 走 AI 对话 -> 返回回复
     * <p>
     * 语音识别出的文字会作为普通文本消息进入 AI 对话链路，
     * 保持和文本消息一致的记忆和人格行为。
     * </p>
     *
     * @param client   ILinkClient 实例
     * @param item     包含语音的消息项
     * @param sessionKey 会话键（botId:fromUserId），用于对话记忆隔离
     * @return 回复文字
     */
    public String handleVoice(ILinkClient client, MessageItem item, String sessionKey) {
        try {
            VoiceItem voiceItem = item.getVoice_item();
            String text = voiceService.recognizeVoice(client, voiceItem);

            if (text == null || text.isBlank()) {
                return "你说的啥，我没听清，打字给我呗";
            }

            log.info("语音识别成功 [session={}]: {}", sessionKey, text);
            // 将识别出的文字当作普通文本消息走 AI 对话
            return aiChatService.chat(sessionKey, text);

        } catch (Exception e) {
            log.error("处理语音消息失败 [session={}]", sessionKey, e);
            return "语音听不了，你打字吧";
        }
    }

    /**
     * 处理视频消息：下载视频 -> 抽帧并生成中性描述 -> 由主聊天服务按人设回复
     *
     * @param client   ILinkClient 实例
     * @param item     包含视频的消息项
     * @param sessionKey 会话键（botId:fromUserId），用于对话记忆隔离
     * @return 回复文字
     */
    public String handleVideo(ILinkClient client, MessageItem item, String sessionKey) {
        if (!aiChatService.isPersonaReady(sessionKey)) {
            return aiChatService.chat(sessionKey, "（对方发来一段视频，先继续完成聊天伙伴的初始设置）");
        }
        try {
            byte[] videoBytes = client.downloadVideoFromMessageItem(item);
            String description = videoService.describeVideo(videoBytes);
            return aiChatService.chat(
                    sessionKey, "（用户发来一段视频，视频内容描述：" + description + "）");
        } catch (IOException e) {
            log.error("处理视频消息失败 [session={}]", sessionKey, e);
            return "视频加载不出来";
        }
    }

    /**
     * 尝试将回复文字合成为语音。
     * 根据配置的概率和文本长度决定是否生成语音。
     *
     * @param replyText AI 回复文字
     * @return SILK 音频结果，不生成语音时返回 null
     */
    public VoiceService.SilkAudioResult maybeSynthesizeVoice(String replyText) {
        if (!voiceService.shouldReplyWithVoice(replyText)) return null;
        return voiceService.synthesizeVoice(replyText);
    }

    /**
     * 按当前会话的人设音色合成语音；未设置时沿用全局默认音色。
     */
    public VoiceService.SilkAudioResult maybeSynthesizeVoice(String sessionKey, String replyText) {
        if (!voiceService.shouldReplyWithVoice(replyText)) return null;
        return voiceService.synthesizeVoice(replyText, personaStore.getTtsVoice(sessionKey));
    }
}
