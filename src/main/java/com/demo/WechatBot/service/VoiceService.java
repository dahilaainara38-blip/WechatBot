package com.demo.WechatBot.service;

import com.alibaba.dashscope.audio.asr.recognition.Recognition;
import com.alibaba.dashscope.audio.asr.recognition.RecognitionParam;
import com.alibaba.dashscope.audio.asr.recognition.RecognitionResult;
import com.alibaba.dashscope.audio.asr.recognition.timestamp.Sentence;
import com.alibaba.dashscope.audio.tts.SpeechSynthesisResult;
import com.alibaba.dashscope.audio.ttsv2.SpeechSynthesisAudioFormat;
import com.alibaba.dashscope.audio.ttsv2.SpeechSynthesisParam;
import com.alibaba.dashscope.audio.ttsv2.SpeechSynthesizer;
import com.alibaba.dashscope.common.ResultCallback;
import com.alibaba.dashscope.common.Status;
import com.demo.WechatBot.config.AiProperties;
import com.demo.WechatBot.config.MultimodalProperties;
import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.model.VoiceItem;
import io.github.kasukusakura.silkcodec.SilkCoder;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 语音服务：语音识别 (ASR) + 语音合成 (TTS) + SILK 编解码。
 * <p>
 * 微信语音使用 SILK 编码格式，DashScope ASR/TTS 使用 PCM/WAV，
 * 本服务负责在两者之间做格式桥接。
 * </p>
 */
@Service
public class VoiceService {

    private static final Logger log = LoggerFactory.getLogger(VoiceService.class);

    private final AiProperties aiProperties;
    private final MultimodalProperties multimodalProperties;
    private String apiKey;

    public VoiceService(AiProperties aiProperties,
                        MultimodalProperties multimodalProperties) {
        this.aiProperties = aiProperties;
        this.multimodalProperties = multimodalProperties;
    }

    @PostConstruct
    public void init() {
        this.apiKey = aiProperties.getApiKey();
    }

    // ==================== 语音识别 (ASR) ====================

    /**
     * 从收到的微信语音消息中识别文字。
     * <p>
     * 策略：
     * 1. 优先使用 VoiceItem.getText()，ILINK 服务端可能已做语音转文字
     * 2. 否则下载 SILK 音频，解码为 PCM，调用 DashScope 实时 ASR
     * </p>
     *
     * @param client    ILinkClient 实例（用于下载媒体）
     * @param voiceItem 收到的语音消息项
     * @return 识别出的文字，识别失败返回 null
     */
    public String recognizeVoice(ILinkClient client, VoiceItem voiceItem) {
        // 策略1：服务端已提供转文字
        String serverText = voiceItem.getText();
        if (serverText != null && !serverText.isBlank()) {
            log.info("使用服务端语音转文字: {}", serverText);
            return serverText;
        }

        // 策略2：下载 SILK -> PCM -> DashScope ASR
        try {
            byte[] silkData = client.downloadMedia(voiceItem.getMedia());
            int sampleRate = voiceItem.getSample_rate() != null ? voiceItem.getSample_rate() : 24000;

            // SILK -> PCM
            byte[] pcmData = silkToPcm(silkData, sampleRate);

            // 调用 DashScope 实时 ASR（直接推送 PCM 流）
            String text = dashscopeAsr(pcmData, sampleRate);
            log.info("DashScope ASR 识别结果: {}", text);
            return text;

        } catch (Exception e) {
            log.error("语音识别失败", e);
            return null;
        }
    }

    /**
     * SILK 格式解码为 PCM 原始音频数据
     */
    private byte[] silkToPcm(byte[] silkData, int sampleRate) throws IOException {
        ByteArrayOutputStream pcmOut = new ByteArrayOutputStream();
        try (ByteArrayInputStream silkIn = new ByteArrayInputStream(silkData)) {
            SilkCoder.decode(silkIn, pcmOut);
        }
        return pcmOut.toByteArray();
    }

    /**
     * 调用 DashScope Paraformer 实时语音识别。
     * 使用流式推送 PCM 数据，通过 ResultCallback 收集识别结果。
     *
     * @param pcmData    PCM 音频数据
     * @param sampleRate 采样率
     * @return 识别出的完整文字
     */
    private String dashscopeAsr(byte[] pcmData, int sampleRate) throws Exception {
        Recognition recognizer = new Recognition();
        RecognitionParam param = RecognitionParam.builder()
                .model(aiProperties.getAsrModel())
                .apiKey(apiKey)
                .format("pcm")
                .sampleRate(sampleRate)
                .build();

        StringBuilder asrResult = new StringBuilder();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Exception> errorRef = new AtomicReference<>();

        recognizer.call(param, new ResultCallback<RecognitionResult>() {
            @Override
            public void onOpen(Status status) {
                log.debug("ASR 连接已建立");
                // 分段推送 PCM 音频数据
                int chunkSize = 3200; // 每次推送 100ms 的音频 (16bit * 16kHz * 0.1s)
                int offset = 0;
                while (offset < pcmData.length) {
                    int len = Math.min(chunkSize, pcmData.length - offset);
                    byte[] chunk = new byte[len];
                    System.arraycopy(pcmData, offset, chunk, 0, len);
                    recognizer.sendAudioFrame(ByteBuffer.wrap(chunk));
                    offset += len;
                    // 控制推送速率，避免服务端缓冲溢出
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                // 推送完毕，发送停止信号
                recognizer.stop();
            }

            @Override
            public void onEvent(RecognitionResult result) {
                Sentence sentence = result.getSentence();
                if (sentence != null && sentence.getText() != null) {
                    if (result.isSentenceEnd()) {
                        // 句子结束，追加最终结果到外部 StringBuilder
                        asrResult.append(sentence.getText());
                    }
                }
            }

            @Override
            public void onComplete() {
                latch.countDown();
            }

            @Override
            public void onError(Exception e) {
                errorRef.set(e);
                latch.countDown();
            }
        });

        // 等待识别完成，最多 30 秒
        if (!latch.await(30, TimeUnit.SECONDS)) {
            log.warn("ASR 识别超时");
            return null;
        }

        if (errorRef.get() != null) {
            log.error("ASR 识别出错", errorRef.get());
            return null;
        }

        String text = asrResult.toString().trim();
        return text.isEmpty() ? null : text;
    }

    // ==================== 语音合成 (TTS) ====================

    /**
     * 将文字合成为微信语音消息所需的 SILK 格式。
     * <p>
     * 流程：Text -> DashScope TTS -> PCM -> SILK 编码
     * </p>
     *
     * @param text 要合成的文字
     * @return SilkAudioResult 包含 SILK 字节数据、时长、采样率；失败返回 null
     */
    public SilkAudioResult synthesizeVoice(String text) {
        return synthesizeVoice(text, null);
    }

    /**
     * 使用指定音色合成语音；音色为空时使用全局默认配置。
     */
    public SilkAudioResult synthesizeVoice(String text, String voice) {
        try {
            // 1. 调用 DashScope TTS 生成 PCM 音频
            int sampleRate = 24000;
            byte[] pcmData = dashscopeTts(text, sampleRate, voice);
            if (pcmData == null || pcmData.length == 0) {
                log.warn("TTS 返回空音频");
                return null;
            }

            // 2. PCM -> SILK 编码
            ByteArrayOutputStream silkOut = new ByteArrayOutputStream();
            try (ByteArrayInputStream pcmIn = new ByteArrayInputStream(pcmData)) {
                SilkCoder.encode(pcmIn, silkOut, sampleRate);
            }
            byte[] silkBytes = silkOut.toByteArray();

            // 3. 估算播放时长（PCM 字节数 / (采样率 * 2字节每样本) * 1000ms）
            int playTimeMs = (int) ((pcmData.length / ((double) sampleRate * 2)) * 1000);

            log.info("语音合成完成: 文字={}字, SILK={}字节, 时长={}ms",
                    text.length(), silkBytes.length, playTimeMs);

            return new SilkAudioResult(silkBytes, playTimeMs, sampleRate);

        } catch (Exception e) {
            log.error("语音合成失败", e);
            return null;
        }
    }

    /**
     * 调用 DashScope CosyVoice TTS 合成语音。
     * 使用 SpeechSynthesizer.call(text) 同步方式获取完整音频。
     *
     * @param text       要合成的文字
     * @param sampleRate 采样率
     * @return PCM 音频数据
     */
    private byte[] dashscopeTts(String text, int sampleRate, String voice) throws Exception {
        SpeechSynthesisAudioFormat format = SpeechSynthesisAudioFormat.PCM_24000HZ_MONO_16BIT;
        // 根据采样率选择格式
        for (SpeechSynthesisAudioFormat f : SpeechSynthesisAudioFormat.values()) {
            if (f.getSampleRate() == sampleRate && f.name().startsWith("PCM_")) {
                format = f;
                break;
            }
        }

        String selectedVoice = (voice == null || voice.isBlank())
                ? aiProperties.getTtsVoice() : voice;
        SpeechSynthesisParam param = SpeechSynthesisParam.builder()
                .apiKey(apiKey)
                .model(aiProperties.getTtsModel())
                .voice(selectedVoice)
                .format(format)
                .build();

        SpeechSynthesizer synthesizer = new SpeechSynthesizer(param, null);
        ByteBuffer audioBuffer = synthesizer.call(text);

        if (audioBuffer == null) {
            return new byte[0];
        }

        // 将 ByteBuffer 转为 byte[]
        byte[] audioData = new byte[audioBuffer.remaining()];
        audioBuffer.get(audioData);
        return audioData;
    }

    /**
     * 判断本次回复是否应该用语音发送。
     基于配置的概率随机决定，且文本过长时不发语音。
     */
    public boolean shouldReplyWithVoice(String replyText) {
        if (!multimodalProperties.isVoiceReplyEnabled()) return false;
        if (replyText == null || replyText.length() > 50) return false;
        return ThreadLocalRandom.current().nextDouble() < multimodalProperties.getVoiceReplyProbability();
    }

    /**
     * SILK 音频结果封装
     */
    public record SilkAudioResult(byte[] silkData, int playTimeMs, int sampleRate) {}
}
