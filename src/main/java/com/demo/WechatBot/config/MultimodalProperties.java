package com.demo.WechatBot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "ai.multimodal")
public class MultimodalProperties {

    private boolean voiceReplyEnabled = true;
    private double voiceReplyProbability = 0.3;
    private String tempDir = "work/media";

    public boolean isVoiceReplyEnabled() {
        return voiceReplyEnabled;
    }

    public void setVoiceReplyEnabled(boolean voiceReplyEnabled) {
        this.voiceReplyEnabled = voiceReplyEnabled;
    }

    public double getVoiceReplyProbability() {
        return voiceReplyProbability;
    }

    public void setVoiceReplyProbability(double voiceReplyProbability) {
        this.voiceReplyProbability = voiceReplyProbability;
    }

    public String getTempDir() {
        return tempDir;
    }

    public void setTempDir(String tempDir) {
        this.tempDir = tempDir;
    }
}
