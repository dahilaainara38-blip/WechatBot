package com.demo.WechatBot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * DeepSeek 主对话模型配置。
 * 通过 OpenAI 兼容协议接入，默认指向 DeepSeek 官方端点。
 */
@Component
@ConfigurationProperties(prefix = "ai.deepseek")
public class DeepSeekProperties {

    private String apiKey;
    private String modelName = "deepseek-chat";
    private String baseUrl = "https://api.deepseek.com";
    private int timeoutSeconds = 25;
    private int maxRetries = 1;

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

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public void setMaxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
    }
}
