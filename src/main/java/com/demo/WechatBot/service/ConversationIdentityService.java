package com.demo.WechatBot.service;

import org.springframework.stereotype.Service;

/**
 * 统一解析当前会话中的称呼、用户标签和人设正文，避免各链路写死角色与性别。
 */
@Service
public class ConversationIdentityService {

    public record ConversationIdentity(String botName, String botLabel, String userLabel,
                                       String personaText) {
    }

    private final BotPersonaStore personaStore;

    public ConversationIdentityService(BotPersonaStore personaStore) {
        this.personaStore = personaStore;
    }

    public ConversationIdentity forSession(String sessionKey) {
        String botName = personaStore.getName(sessionKey);
        if (botName == null || botName.isBlank() || "对方".equals(botName)) {
            botName = "聊天伙伴";
        }
        return new ConversationIdentity(
                botName, botName, "用户", personaStore.getPersonaText(sessionKey));
    }
}
