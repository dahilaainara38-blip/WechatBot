package com.demo.WechatBot.service;

import com.demo.WechatBot.config.AiProperties;
import dev.langchain4j.data.image.Image;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.dashscope.QwenChatModel;
import dev.langchain4j.model.output.Response;
import jakarta.annotation.PostConstruct;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.Java2DFrameConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * 视频识别服务。
 * <p>
 * 视频无法直接输入 LLM，策略是用 FFmpeg 抽取 N 个关键帧，
 * 将每帧作为图片送入 Qwen-VL 多模态模型综合理解。
 * </p>
 */
@Service
public class VideoService {

    private static final Logger log = LoggerFactory.getLogger(VideoService.class);
    private static final int MAX_FRAMES = 4;

    private final AiProperties aiProperties;
    private ChatLanguageModel visionModel;

    private static final String VIDEO_SYSTEM_PROMPT = """
            请根据这些视频关键帧，客观概括视频内容，供后续聊天模型理解。
            要求：
            - 说明主要人物、物体、动作、场景变化和清晰可读的文字
            - 一到四句话，具体、简洁
            - 不要扮演任何角色，不要生成聊天回复，不要猜测关键帧之外的信息
            - 如果画面模糊或无法判断，直接说明不确定的部分
            """;

    public VideoService(AiProperties aiProperties) {
        this.aiProperties = aiProperties;
    }

    @PostConstruct
    public void init() {
        this.visionModel = QwenChatModel.builder()
                .apiKey(aiProperties.getApiKey())
                .modelName(aiProperties.getVlModelName())
                .build();
        log.info("VideoService initialized with VL model: {}", aiProperties.getVlModelName());
    }

    /**
     * 生成中性的视频内容概括。
     * <p>
     * 1. 用 FFmpeg 从视频中均匀抽取关键帧
     * 2. 将所有帧作为 ImageContent 一次性发给 Qwen-VL
     * 3. VL 模型综合所有帧生成理解
     * </p>
     *
     * @param videoBytes 视频二进制数据
     * @return 视频内容描述
     */
    public String describeVideo(byte[] videoBytes) {
        try {
            List<byte[]> frames = extractKeyFrames(videoBytes);
            if (frames.isEmpty()) {
                return "视频内容无法识别";
            }

            List<Content> contents = new ArrayList<>();
            contents.add(TextContent.from("这是视频里的几帧画面"));
            for (byte[] frame : frames) {
                String base64 = Base64.getEncoder().encodeToString(frame);
                Image img = Image.builder()
                        .base64Data(base64)
                        .mimeType("image/jpeg")
                        .build();
                contents.add(ImageContent.from(img));
            }

            UserMessage userMessage = UserMessage.from(contents);
            SystemMessage systemMessage = SystemMessage.from(VIDEO_SYSTEM_PROMPT);

            Response<AiMessage> response = visionModel.generate(
                    List.of(systemMessage, userMessage)
            );

            String reply = response.content().text();
            log.info("视频内容描述（{}帧）: {}", frames.size(), reply);
            return reply;

        } catch (Exception e) {
            log.error("视频识别失败", e);
            return "视频内容无法识别";
        }
    }

    /**
     * 用 FFmpeg 从视频中均匀抽取关键帧，每帧转为 JPEG 字节数组
     */
    private List<byte[]> extractKeyFrames(byte[] videoBytes) throws Exception {
        List<byte[]> frames = new ArrayList<>();

        Path tempFile = Files.createTempFile("video", ".mp4");
        Files.write(tempFile, videoBytes);

        try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(tempFile.toString())) {
            grabber.start();

            int totalFrames = grabber.getLengthInVideoFrames();
            int step = Math.max(1, totalFrames / MAX_FRAMES);

            Java2DFrameConverter converter = new Java2DFrameConverter();

            for (int i = 0; i < totalFrames && frames.size() < MAX_FRAMES; i += step) {
                grabber.setVideoFrameNumber(i);
                Frame frame = grabber.grabImage();
                if (frame == null) continue;

                BufferedImage img = converter.convert(frame);
                if (img == null) continue;

                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                ImageIO.write(img, "jpg", baos);
                frames.add(baos.toByteArray());
            }

            grabber.stop();
        } finally {
            Files.deleteIfExists(tempFile);
        }

        return frames;
    }
}
