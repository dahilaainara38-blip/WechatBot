package com.demo.WechatBot;

import com.demo.WechatBot.service.ConversationIdentityService;
import com.demo.WechatBot.service.ProfileExtractor;
import com.demo.WechatBot.service.StatefulChatMemory;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class StatefulChatMemoryTest {

    @Test
    void staleSummaryCannotOverwriteNewerRevision() {
        ChatMemoryStore store = mock(ChatMemoryStore.class);
        StatefulChatMemory memory = new StatefulChatMemory(
                "bot:user", store, mock(ChatLanguageModel.class), mock(ProfileExtractor.class),
                mock(ConversationIdentityService.class), 8);
        ReflectionTestUtils.setField(memory, "summaryRevision", 2L);

        ReflectionTestUtils.invokeMethod(memory, "applySummaryToStore", "过期摘要", 1L);

        verify(store, never()).updateMessages(any(), anyList());
        memory.shutdown();
    }
}
