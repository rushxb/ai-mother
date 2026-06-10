package dev.langchain4j.model.openai.internal.chat;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;

import java.util.List;

import static dev.langchain4j.internal.Utils.isNullOrEmpty;

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

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder extends AiMessage.Builder {

        private String text;
        private List<ToolExecutionRequest> toolExecutionRequests;
        private String reasoningContent;

        @Override
        public Builder text(String text) {
            this.text = text;
            return this;
        }

        @Override
        public Builder toolExecutionRequests(List<ToolExecutionRequest> toolExecutionRequests) {
            this.toolExecutionRequests = toolExecutionRequests;
            return this;
        }

        public Builder reasoningContent(String reasoningContent) {
            this.reasoningContent = reasoningContent;
            return this;
        }

        @Override
        public ReasoningAiMessage build() {
            if (isNullOrEmpty(toolExecutionRequests)) {
                return new ReasoningAiMessage(text == null ? "" : text, reasoningContent);
            }
            if (text == null) {
                return new ReasoningAiMessage(toolExecutionRequests, reasoningContent);
            }
            return new ReasoningAiMessage(text, toolExecutionRequests, reasoningContent);
        }
    }
}
