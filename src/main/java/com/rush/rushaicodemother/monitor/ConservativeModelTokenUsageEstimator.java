package com.rush.rushaicodemother.monitor;

import com.rush.rushaicodemother.orchestration.context.AiContextTokenEstimator;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.JacksonChatMessageJsonCodec;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 使用生产 tokenizer 和安全裕量估算完整模型请求/响应。
 *
 * <p>输入使用结构化消息序列化，并把 tool schema 计入上下文；输出使用完整 AI
 * message，因此 tool call 参数不会被遗漏。底层 tokenizer 本身带有可配置安全裕量。</p>
 */
@Component
@RequiredArgsConstructor
public final class ConservativeModelTokenUsageEstimator implements ModelTokenUsageEstimator {

    private static final int MESSAGE_ENVELOPE_TOKENS = 4;
    private static final int TOOL_ENVELOPE_TOKENS = 4;

    private final AiContextTokenEstimator textEstimator;
    private final JacksonChatMessageJsonCodec messageCodec = new JacksonChatMessageJsonCodec();

    @Override
    public EstimatedModelTokenUsage estimate(ChatRequest request, ChatResponse response) {
        int promptTokens = estimatePrompt(request);
        int completionTokens = response == null
                ? configuredMaximumOutputTokens(request)
                : estimateResponse(response);
        int totalTokens = saturatedAdd(promptTokens, completionTokens);
        return new EstimatedModelTokenUsage(
                Math.min(promptTokens, totalTokens),
                totalTokens - Math.min(promptTokens, totalTokens),
                totalTokens
        );
    }

    private int configuredMaximumOutputTokens(ChatRequest request) {
        Integer maximumOutputTokens = request == null ? null : request.maxOutputTokens();
        return maximumOutputTokens == null ? 0 : Math.max(0, maximumOutputTokens);
    }

    private int estimatePrompt(ChatRequest request) {
        if (request == null) {
            return 0;
        }
        int tokens = 0;
        if (request.messages() != null) {
            for (ChatMessage message : request.messages()) {
                tokens = saturatedAdd(tokens, estimateMessage(message));
                tokens = saturatedAdd(tokens, MESSAGE_ENVELOPE_TOKENS);
            }
        }
        if (request.toolSpecifications() != null) {
            for (var tool : request.toolSpecifications()) {
                if (tool == null) {
                    continue;
                }
                tokens = saturatedAdd(tokens, textEstimator.estimate(tool.toJson()));
                tokens = saturatedAdd(tokens, TOOL_ENVELOPE_TOKENS);
            }
        }
        return tokens;
    }

    private int estimateResponse(ChatResponse response) {
        if (response == null || response.aiMessage() == null) {
            return 0;
        }
        return saturatedAdd(estimateMessage(response.aiMessage()), MESSAGE_ENVELOPE_TOKENS);
    }

    private int estimateMessage(ChatMessage message) {
        if (message == null) {
            return 0;
        }
        try {
            return Math.max(0, textEstimator.estimate(messageCodec.messageToJson(message)));
        } catch (RuntimeException serializationFailure) {
            return Math.max(0, textEstimator.estimate(String.valueOf(message)));
        }
    }

    private int saturatedAdd(int left, int right) {
        long result = (long) Math.max(0, left) + Math.max(0, right);
        return result > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) result;
    }
}
