package dev.langchain4j.model.openai.internal.chat;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;

import java.util.List;

public class ReasoningAiMessage extends AiMessage {

    private final String reasoningContent;

    public ReasoningAiMessage(String text, String reasoningContent) {
        super(text);
        this.reasoningContent = reasoningContent;
    }

    public ReasoningAiMessage(List<ToolExecutionRequest> toolExecutionRequests, String reasoningContent) {
        super(toolExecutionRequests);
        this.reasoningContent = reasoningContent;
    }

    public ReasoningAiMessage(String text, List<ToolExecutionRequest> toolExecutionRequests, String reasoningContent) {
        super(text, toolExecutionRequests);
        this.reasoningContent = reasoningContent;
    }

    public String reasoningContent() {
        return reasoningContent;
    }
}
