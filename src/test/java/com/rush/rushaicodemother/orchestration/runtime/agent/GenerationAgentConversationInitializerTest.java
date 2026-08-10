package com.rush.rushaicodemother.orchestration.runtime.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rush.rushaicodemother.orchestration.context.AgentConversationFolder;
import com.rush.rushaicodemother.orchestration.context.AgentConversationTokenAccountant;
import com.rush.rushaicodemother.orchestration.context.AgentConversationWindowPolicy;
import com.rush.rushaicodemother.orchestration.context.AiContextPackBudgetProperties;
import com.rush.rushaicodemother.orchestration.context.OpenAiCompatibleContextTokenEstimator;
import com.rush.rushaicodemother.orchestration.context.ToolRoundPathExtractor;
import com.rush.rushaicodemother.service.ChatHistoryService;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GenerationAgentConversationInitializerTest {

    @Test
    void initializationMustReplaceHistoricalSystemPromptAndAppendCurrentUserPromptOnce() {
        ChatMemoryStore memoryStore = new InMemoryChatMemoryStore();
        ChatHistoryService historyService = mock(ChatHistoryService.class);
        doAnswer(invocation -> {
            ChatMemory memory = invocation.getArgument(1);
            memory.add(SystemMessage.from("旧系统提示"));
            memory.add(UserMessage.from("历史需求"));
            memory.add(AiMessage.from("历史答复"));
            return 3;
        }).when(historyService).loadChatHistoryToMemory(eq(11L), any(), eq(4));
        GenerationAgentPromptResolver promptResolver = mock(GenerationAgentPromptResolver.class);
        InvocationContext invocationContext = mock(InvocationContext.class);
        GenerationAgentExecutionRequest request = mock(GenerationAgentExecutionRequest.class);
        when(request.appId()).thenReturn(11L);
        when(request.userPrompt()).thenReturn("生成管理后台");
        when(request.codeGenType()).thenReturn(
                com.rush.rushaicodemother.model.enums.CodeGenTypeEnum.VUE_PROJECT);
        when(promptResolver.resolve(request.codeGenType(), invocationContext))
                .thenReturn("当前系统提示");

        ChatMemory memory = new GenerationAgentConversationInitializer(
                memoryStore, historyService, promptResolver, windowPolicy())
                .initialize(request, invocationContext);

        List<ChatMessage> messages = memory.messages();
        assertEquals(4, messages.size());
        assertEquals("当前系统提示", assertInstanceOf(
                SystemMessage.class, messages.getFirst()).text());
        assertEquals("历史需求", assertInstanceOf(
                UserMessage.class, messages.get(1)).singleText());
        assertEquals("历史答复", assertInstanceOf(
                AiMessage.class, messages.get(2)).text());
        assertEquals("生成管理后台", assertInstanceOf(
                UserMessage.class, messages.getLast()).singleText());
        verify(historyService).loadChatHistoryToMemory(11L, memory, 4);
    }

    @Test
    void initializationMustNotDuplicateCurrentPromptAlreadyPersistedInHistory() {
        ChatMemoryStore memoryStore = new InMemoryChatMemoryStore();
        ChatHistoryService historyService = mock(ChatHistoryService.class);
        doAnswer(invocation -> {
            ChatMemory memory = invocation.getArgument(1);
            memory.add(UserMessage.from("继续完善登录页"));
            return 1;
        }).when(historyService).loadChatHistoryToMemory(eq(12L), any(), eq(4));
        GenerationAgentPromptResolver promptResolver = mock(GenerationAgentPromptResolver.class);
        when(promptResolver.resolve(any(), any())).thenReturn("系统提示");
        GenerationAgentExecutionRequest request = mock(GenerationAgentExecutionRequest.class);
        when(request.appId()).thenReturn(12L);
        when(request.userPrompt()).thenReturn("继续完善登录页");
        when(request.codeGenType()).thenReturn(
                com.rush.rushaicodemother.model.enums.CodeGenTypeEnum.VUE_PROJECT);

        List<ChatMessage> messages = new GenerationAgentConversationInitializer(
                memoryStore, historyService, promptResolver, windowPolicy())
                .initialize(request, mock(InvocationContext.class))
                .messages();

        long currentPromptCount = messages.stream()
                .filter(UserMessage.class::isInstance)
                .map(UserMessage.class::cast)
                .filter(UserMessage::hasSingleText)
                .filter(message -> "继续完善登录页".equals(message.singleText()))
                .count();
        assertEquals(1L, currentPromptCount);
    }

    /** 使用与生产一致的窗口策略，确保测试覆盖真实的折叠与预算语义。 */
    private static AgentConversationWindowPolicy windowPolicy() {
        return new AgentConversationWindowPolicy(
                new AgentConversationFolder(new ToolRoundPathExtractor(new ObjectMapper())),
                new AgentConversationTokenAccountant(
                        new OpenAiCompatibleContextTokenEstimator(
                                new AiContextPackBudgetProperties())));
    }

    private static final class InMemoryChatMemoryStore implements ChatMemoryStore {
        private final List<ChatMessage> messages = new ArrayList<>();

        @Override
        public synchronized List<ChatMessage> getMessages(Object memoryId) {
            return List.copyOf(messages);
        }

        @Override
        public synchronized void updateMessages(Object memoryId, List<ChatMessage> messages) {
            this.messages.clear();
            this.messages.addAll(messages);
        }

        @Override
        public synchronized void deleteMessages(Object memoryId) {
            messages.clear();
        }
    }
}
