package com.rush.rushaicodemother.ai.model.failover;

import com.rush.rushaicodemother.core.error.GenerationErrorClassifier;
import com.rush.rushaicodemother.monitor.AiModelMetricsCollector;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class FailoverChatModelTest {

    private final ChatRequest request = mock(ChatRequest.class);
    private final AiModelMetricsCollector metrics = mock(AiModelMetricsCollector.class);

    @Test
    void transientPrimaryFailureMustUseNextCandidateAndRecordMetric() {
        ChatModel primary = mock(ChatModel.class);
        ChatModel fallback = mock(ChatModel.class);
        ChatResponse expected = mock(ChatResponse.class);
        when(primary.chat(request)).thenThrow(new RuntimeException("503 Service Unavailable"));
        when(fallback.chat(request)).thenReturn(expected);

        ChatResponse actual = model(primary, fallback).chat(request);

        assertSame(expected, actual);
        verify(metrics).recordFailover(
                "provider-a", "primary", "provider-b", "fallback",
                GenerationErrorClassifier.CATEGORY_MODEL_UNAVAILABLE);
    }

    @Test
    void authenticationFailureMustNotFailOver() {
        ChatModel primary = mock(ChatModel.class);
        ChatModel fallback = mock(ChatModel.class);
        RuntimeException failure = new RuntimeException("401 Unauthorized");
        when(primary.chat(request)).thenThrow(failure);

        RuntimeException actual = assertThrows(RuntimeException.class,
                () -> model(primary, fallback).chat(request));

        assertSame(failure, actual);
        verifyNoInteractions(fallback, metrics);
    }

    @Test
    void quotaFailureMustNotFailOver() {
        ChatModel primary = mock(ChatModel.class);
        ChatModel fallback = mock(ChatModel.class);
        RuntimeException failure = new RuntimeException("insufficient quota");
        when(primary.chat(request)).thenThrow(failure);

        RuntimeException actual = assertThrows(RuntimeException.class,
                () -> model(primary, fallback).chat(request));

        assertSame(failure, actual);
        verifyNoInteractions(fallback, metrics);
    }

    @Test
    void unknownProgrammingFailureMustNotBeHiddenByFailover() {
        ChatModel primary = mock(ChatModel.class);
        ChatModel fallback = mock(ChatModel.class);
        NullPointerException failure = new NullPointerException("unexpected null");
        when(primary.chat(request)).thenThrow(failure);

        RuntimeException actual = assertThrows(RuntimeException.class,
                () -> model(primary, fallback).chat(request));

        assertSame(failure, actual);
        verifyNoInteractions(fallback, metrics);
    }

    @Test
    void terminalFailureMustRetainEarlierFailuresAsSuppressed() {
        ChatModel primary = mock(ChatModel.class);
        ChatModel fallback = mock(ChatModel.class);
        RuntimeException first = new RuntimeException("503 primary unavailable");
        RuntimeException terminal = new RuntimeException("503 fallback unavailable");
        when(primary.chat(request)).thenThrow(first);
        when(fallback.chat(request)).thenThrow(terminal);

        RuntimeException actual = assertThrows(RuntimeException.class,
                () -> model(primary, fallback).chat(request));

        assertSame(terminal, actual);
        assertEquals(List.of(first), List.of(actual.getSuppressed()));
    }

    @Test
    void supportedCapabilitiesMustBeIntersectionOfAllCandidates() {
        ChatModel primary = mock(ChatModel.class);
        ChatModel fallback = mock(ChatModel.class);
        when(primary.supportedCapabilities())
                .thenReturn(Set.of(Capability.RESPONSE_FORMAT_JSON_SCHEMA));
        when(fallback.supportedCapabilities()).thenReturn(Set.of());

        Set<Capability> capabilities = model(primary, fallback).supportedCapabilities();

        assertFalse(capabilities.contains(Capability.RESPONSE_FORMAT_JSON_SCHEMA));
    }

    @Test
    void totalTimeoutMustPreventStartingAnotherCandidate() {
        ChatModel primary = mock(ChatModel.class);
        ChatModel fallback = mock(ChatModel.class);
        RuntimeException first = new RuntimeException("503 primary unavailable");
        AtomicLong now = new AtomicLong();
        when(primary.chat(request)).thenAnswer(invocation -> {
            now.set(Duration.ofSeconds(5).toNanos());
            throw first;
        });
        FailoverChatModel model = new FailoverChatModel(List.of(
                new AiModelCandidate<>("provider-a", "primary", primary),
                new AiModelCandidate<>("provider-b", "fallback", fallback)
        ), metrics, Duration.ofSeconds(5), now::get);

        dev.langchain4j.exception.TimeoutException timeout = assertThrows(
                dev.langchain4j.exception.TimeoutException.class,
                () -> model.chat(request));

        assertEquals(List.of(first), List.of(timeout.getSuppressed()));
        verifyNoInteractions(fallback);
    }

    private FailoverChatModel model(ChatModel primary, ChatModel fallback) {
        return new FailoverChatModel(List.of(
                new AiModelCandidate<>("provider-a", "primary", primary),
                new AiModelCandidate<>("provider-b", "fallback", fallback)
        ), metrics);
    }
}
