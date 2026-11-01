package com.demo.WechatBot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "ai.dashscope")
public class AiProperties {

    private String apiKey;
    private String modelName = "qwen-plus";
    private String vlModelName = "qwen-vl-max";

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

}
