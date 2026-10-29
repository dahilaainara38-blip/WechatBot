package com.demo.WechatBot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "ai.dashscope")
public class AiProperties {

    private String apiKey;
    private String modelName = "qwen-plus";
    private String vlModelName = "qwen-vl-max";
    private String ttsModel = "cosyvoice-v1";
    private String ttsVoice = "longxiaochun";
    private String ttsMaleVoice = "longxiaocheng";
    private String ttsFormat = "PCM_24000HZ_MONO_16BIT";
    private String asrModel = "paraformer-v2";

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getModelName() {
        return modelName;
    }

    public void setModelName(String modelName) {
        this.modelName = modelName;
    }

    public String getVlModelName() {
        return vlModelName;
    }

    public void setVlModelName(String vlModelName) {
        this.vlModelName = vlModelName;
    }

    public String getTtsModel() {
        return ttsModel;
    }

    public void setTtsModel(String ttsModel) {
        this.ttsModel = ttsModel;
    }

    public String getTtsVoice() {
        return ttsVoice;
    }

    public void setTtsVoice(String ttsVoice) {
        this.ttsVoice = ttsVoice;
    }

    public String getTtsMaleVoice() {
        return ttsMaleVoice;
    }

    public void setTtsMaleVoice(String ttsMaleVoice) {
        this.ttsMaleVoice = ttsMaleVoice;
    }

    public String getTtsFormat() {
        return ttsFormat;
    }

    public void setTtsFormat(String ttsFormat) {
        this.ttsFormat = ttsFormat;
    }

    public String getAsrModel() {
        return asrModel;
    }

    public void setAsrModel(String asrModel) {
        this.asrModel = asrModel;
    }
}
