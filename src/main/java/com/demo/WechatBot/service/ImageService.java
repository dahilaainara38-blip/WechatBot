package com.demo.WechatBot.service;

import com.demo.WechatBot.config.AiProperties;
import dev.langchain4j.data.image.Image;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.dashscope.QwenChatModel;
import dev.langchain4j.model.output.Response;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Base64;
import java.util.List;

/**
 * 图片内容描述服务。
 * <p>
 * 使用 Qwen-VL 多模态模型生成中性图片描述，最终回复由主聊天服务按当前人设生成。
 * QwenChatModel 内部通过模型名判断是否为多模态模型（含 "-vl-"），
 * 自动切换到 MultiModalConversation 调用路径。
 * </p>
 */
@Service
public class ImageService {

    private static final Logger log = LoggerFactory.getLogger(ImageService.class);

    private final AiProperties aiProperties;
    private ChatLanguageModel visionModel;

    private static final String VISION_SYSTEM_PROMPT = """
            请客观描述图片内容，供后续聊天模型理解。
            要求：
            - 说明主要人物、物体、动作、场景和清晰可读的文字
            - 一到三句话，具体、简洁
            - 不要扮演任何角色，不要生成聊天回复，不要猜测看不见的信息
            - 如果图片模糊或无法判断，直接说明不确定的部分
            """;

    public ImageService(AiProperties aiProperties) {
        this.aiProperties = aiProperties;
    }

    @PostConstruct
    public void init() {
        this.visionModel = QwenChatModel.builder()
                .apiKey(aiProperties.getApiKey())
                .modelName(aiProperties.getVlModelName())
                .build();
        log.info("ImageService initialized with VL model: {}", aiProperties.getVlModelName());
    }

    /**
     * 生成中性的图片内容描述。
     *
     * @param imageBytes 图片二进制数据（从 ILINK SDK downloadImage 获取）
     * @param mimeType   图片 MIME 类型，如 "image/jpeg"
     * @return 图片内容描述
     */
    public String describeImage(byte[] imageBytes, String mimeType) {
        try {
            String base64Data = Base64.getEncoder().encodeToString(imageBytes);

            Image image = Image.builder()
                    .base64Data(base64Data)
                    .mimeType(mimeType != null ? mimeType : "image/jpeg")
                    .build();

            UserMessage userMessage = UserMessage.from(
                    ImageContent.from(image),
                    TextContent.from("描述这张图片")
            );

            SystemMessage systemMessage = SystemMessage.from(VISION_SYSTEM_PROMPT);

            Response<AiMessage> response = visionModel.generate(
                    List.of(systemMessage, userMessage)
            );

            String reply = response.content().text();
            log.info("图片描述: {}", reply);
            return reply;

        } catch (Exception e) {
            log.error("图片识别失败", e);
            return "图片内容无法识别";
        }
    }
}
