package com.rush.rushaicodemother.monitor;

import com.rush.rushaicodemother.orchestration.context.AiContextTokenEstimator;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConservativeModelTokenUsageEstimatorTest {

    @Test
    void missingProviderUsageMustProduceConservativeInputAndOutputEvidence() {
        AiContextTokenEstimator textEstimator = new CharacterCountingTokenEstimator();
        ConservativeModelTokenUsageEstimator estimator =
                new ConservativeModelTokenUsageEstimator(textEstimator);
        ChatRequest request = ChatRequest.builder()
                .messages(SystemMessage.from("system"), UserMessage.from("build dashboard"))
                .build();
        ChatResponse response = ChatResponse.builder()
                .aiMessage(AiMessage.from("generated project"))
                .build();

        EstimatedModelTokenUsage usage = estimator.estimate(request, response);

        assertTrue(usage.promptTokens() >= "system".length() + "build dashboard".length());
        assertTrue(usage.completionTokens() >= "generated project".length());
        assertEquals(
                usage.promptTokens() + usage.completionTokens(),
                usage.totalTokens()
        );
    }

    @Test
    void startedLedgerMustReserveConfiguredMaximumOutputTokensForCrashRecovery() {
        ConservativeModelTokenUsageEstimator estimator =
                new ConservativeModelTokenUsageEstimator(new CharacterCountingTokenEstimator());
        ChatRequest request = ChatRequest.builder()
                .messages(UserMessage.from("build dashboard"))
                .maxOutputTokens(4_096)
                .build();

        EstimatedModelTokenUsage usage = estimator.estimate(request, null);

        assertEquals(4_096, usage.completionTokens());
        assertEquals(usage.promptTokens() + 4_096, usage.totalTokens());
    }

    private static final class CharacterCountingTokenEstimator implements AiContextTokenEstimator {

        @Override
        public int estimate(String text) {
            return text == null ? 0 : text.length();
        }

        @Override
        public String truncate(String text, int maximumTokens) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String truncateFromEnd(String text, int maximumTokens) {
            throw new UnsupportedOperationException();
        }
    }
}
