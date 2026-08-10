package com.rush.rushaicodemother.orchestration.context;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/**
 * 按模型输入口径估算对话消息的 token 占用。
 *
 * <p>工具请求的参数（往往是整份源码）计入占用，否则「消息条数正常但参数巨大」的
 * 对话会绕过预算；工具结果同理。复用
 * {@link AiContextTokenEstimator} 以保证与其他上下文预算口径一致。</p>
 */
@Component
public class AgentConversationTokenAccountant {

    /** 每条消息的协议开销（role、分隔符等）的保守估计。 */
    private static final int PER_MESSAGE_OVERHEAD_TOKENS = 4;

    private final AiContextTokenEstimator tokenEstimator;

    public AgentConversationTokenAccountant(AiContextTokenEstimator tokenEstimator) {
        this.tokenEstimator = Objects.requireNonNull(tokenEstimator, "token 估算器不能为空");
    }

    /**
     * 估算整段对话的 token 占用。
     *
     * @param messages 消息序列，允许为空
     * @return 估算的 token 数
     */
    public int estimate(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }
        int total = 0;
        for (ChatMessage message : messages) {
            total = Math.addExact(total, estimate(message));
        }
        return total;
    }

    /**
     * 估算单条消息的 token 占用。
     *
     * @param message 消息，允许为空
     * @return 估算的 token 数
     */
    public int estimate(ChatMessage message) {
        if (message == null) {
            return 0;
        }
        return PER_MESSAGE_OVERHEAD_TOKENS + tokenEstimator.estimate(textOf(message));
    }

    /** 抽取消息的可计费文本，包含工具请求参数与工具结果。 */
    private String textOf(ChatMessage message) {
        return switch (message) {
            case SystemMessage system -> nullSafe(system.text());
            case UserMessage user -> user.hasSingleText()
                    ? nullSafe(user.singleText())
                    : nullSafe(user.toString());
            case ToolExecutionResultMessage result -> nullSafe(result.text());
            case AiMessage ai -> aiMessageText(ai);
            default -> nullSafe(message.toString());
        };
    }

    private String aiMessageText(AiMessage message) {
        StringBuilder text = new StringBuilder(nullSafe(message.text()));
        if (!message.hasToolExecutionRequests()) {
            return text.toString();
        }
        for (ToolExecutionRequest request : message.toolExecutionRequests()) {
            text.append('\n').append(nullSafe(request.name()))
                    .append('\n').append(nullSafe(request.arguments()));
        }
        return text.toString();
    }

    private String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
