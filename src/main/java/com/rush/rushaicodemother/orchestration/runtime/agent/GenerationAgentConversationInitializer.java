package com.rush.rushaicodemother.orchestration.runtime.agent;

import com.rush.rushaicodemother.orchestration.context.AgentConversationWindowPolicy;
import com.rush.rushaicodemother.service.ChatHistoryService;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 为新的智能体根尝试重建有界短期会话，并保证当前用户消息幂等。
 *
 * <p>会话窗口由 {@link AgentConversationWindowPolicy} 统一裁决：按 token 预算收敛，
 * 溢出时把最早的工具循环折叠为摘要而非丢弃，模型因此不会「忘记自己改过什么」。</p>
 */
@Component
public class GenerationAgentConversationInitializer {

    private static final int INITIAL_HISTORY_MESSAGES = 4;

    private final ChatMemoryStore chatMemoryStore;
    private final ChatHistoryService chatHistoryService;
    private final GenerationAgentPromptResolver promptResolver;
    private final AgentConversationWindowPolicy conversationWindowPolicy;

    public GenerationAgentConversationInitializer(
            ChatMemoryStore chatMemoryStore,
            ChatHistoryService chatHistoryService,
            GenerationAgentPromptResolver promptResolver,
            AgentConversationWindowPolicy conversationWindowPolicy) {
        this.chatMemoryStore = chatMemoryStore;
        this.chatHistoryService = chatHistoryService;
        this.promptResolver = promptResolver;
        this.conversationWindowPolicy = Objects.requireNonNull(
                conversationWindowPolicy, "短期记忆窗口策略不能为空");
    }

    public ChatMemory initialize(GenerationAgentExecutionRequest request,
                                 InvocationContext invocationContext) {
        ChatMemory memory = conversationWindowPolicy.createGenerationMemory(
                request.appId(), chatMemoryStore);
        chatHistoryService.loadChatHistoryToMemory(
                request.appId(), memory, INITIAL_HISTORY_MESSAGES);

        List<ChatMessage> normalized = new ArrayList<>();
        normalized.add(SystemMessage.from(
                promptResolver.resolve(request.codeGenType(), invocationContext)));
        memory.messages().stream()
                .filter(message -> !(message instanceof SystemMessage))
                .forEach(normalized::add);
        if (!endsWithUserPrompt(normalized, request.userPrompt())) {
            normalized.add(UserMessage.from(request.userPrompt()));
        }
        memory.set(normalized);
        return memory;
    }

    private boolean endsWithUserPrompt(List<ChatMessage> messages, String userPrompt) {
        if (messages.isEmpty()) {
            return false;
        }
        ChatMessage last = messages.getLast();
        return last instanceof UserMessage userMessage
                && userMessage.hasSingleText()
                && userPrompt.equals(userMessage.singleText());
    }
}
