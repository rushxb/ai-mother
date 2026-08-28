package com.rush.rushaicodemother.ai.model.failover;

import com.rush.rushaicodemother.core.error.GenerationErrorClassifier;
import com.rush.rushaicodemother.monitor.AiModelMetricsCollector;
import com.rush.rushaicodemother.monitor.MonitorContext;
import com.rush.rushaicodemother.monitor.MonitorContextHolder;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
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

    @Test
    void totalTimeoutMustReleaseCallerWhenCurrentCandidateIsBlocked() throws Exception {
        ChatModel primary = mock(ChatModel.class);
        ChatModel fallback = mock(ChatModel.class);
        CountDownLatch providerEntered = new CountDownLatch(1);
        CountDownLatch releaseProvider = new CountDownLatch(1);
        when(primary.chat(request)).thenAnswer(invocation -> {
            providerEntered.countDown();
            awaitIgnoringInterruption(releaseProvider);
            return mock(ChatResponse.class);
        });
        FailoverChatModel model = new FailoverChatModel(List.of(
                new AiModelCandidate<>("provider-a", "primary", primary),
                new AiModelCandidate<>("provider-b", "fallback", fallback)
        ), metrics, Duration.ofMillis(100));
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            Future<Throwable> outcome = caller.submit(() -> {
                try {
                    model.chat(request);
                    return null;
                } catch (Throwable failure) {
                    return failure;
                }
            });

            assertTrue(providerEntered.await(1, TimeUnit.SECONDS));
            Throwable failure = outcome.get(1, TimeUnit.SECONDS);
            assertInstanceOf(dev.langchain4j.exception.TimeoutException.class, failure);
            verifyNoInteractions(fallback);
        } finally {
            releaseProvider.countDown();
            caller.shutdownNow();
        }
    }

    @Test
    void supervisedProviderCallMustRetainMonitorContext() {
        ChatModel primary = mock(ChatModel.class);
        ChatResponse expected = mock(ChatResponse.class);
        AtomicReference<MonitorContext> observedContext = new AtomicReference<>();
        when(primary.chat(request)).thenAnswer(invocation -> {
            observedContext.set(MonitorContextHolder.getContext());
            return expected;
        });
        MonitorContext callerContext = MonitorContext.builder()
                .userId("user-21")
                .appId("app-11")
                .taskId("task-31")
                .build();
        MonitorContextHolder.setContext(callerContext);
        try {
            FailoverChatModel model = new FailoverChatModel(List.of(
                    new AiModelCandidate<>("provider-a", "primary", primary)
            ), metrics, Duration.ofSeconds(1));

            assertSame(expected, model.chat(request));
            assertSame(callerContext, observedContext.get());
            assertSame(callerContext, MonitorContextHolder.getContext());
        } finally {
            MonitorContextHolder.clearContext();
        }
    }

    @Test
    void modelTurnAndProviderFailoverMustUseSeparateAdmissions() {
        ChatModel primary = mock(ChatModel.class);
        ChatModel fallback = mock(ChatModel.class);
        ChatResponse expected = mock(ChatResponse.class);
        AtomicInteger modelTurns = new AtomicInteger();
        AtomicInteger providerFailovers = new AtomicInteger();
        when(primary.chat(request)).thenThrow(new RuntimeException("503 primary unavailable"));
        when(fallback.chat(request)).thenReturn(expected);
        FailoverChatModel model = new FailoverChatModel(List.of(
                new AiModelCandidate<>("provider-a", "primary", primary),
                new AiModelCandidate<>("provider-b", "fallback", fallback)
        ), metrics, Duration.ofSeconds(5),
                modelTurns::incrementAndGet, providerFailovers::incrementAndGet);

        ChatResponse actual = model.chat(request);

        assertSame(expected, actual);
        assertEquals(1, modelTurns.get());
        assertEquals(1, providerFailovers.get());
    }

    @Test
    void taskScopedPoolMustRetryConfiguredPrimaryOnTheNextModelTurn() {
        ChatModel primary = mock(ChatModel.class);
        ChatModel fallback = mock(ChatModel.class);
        ChatResponse first = mock(ChatResponse.class);
        ChatResponse second = mock(ChatResponse.class);
        AtomicInteger modelTurns = new AtomicInteger();
        AtomicInteger providerFailovers = new AtomicInteger();
        when(primary.chat(request)).thenThrow(new RuntimeException("503 primary unavailable"));
        when(fallback.chat(request)).thenReturn(first, second);
        FailoverChatModel model = new FailoverChatModel(List.of(
                new AiModelCandidate<>("provider-a", "primary", primary),
                new AiModelCandidate<>("provider-b", "fallback", fallback)
        ), metrics, Duration.ofSeconds(5),
                modelTurns::incrementAndGet, providerFailovers::incrementAndGet);

        assertSame(first, model.chat(request));
        assertSame(second, model.chat(request));

        verify(primary, times(2)).chat(request);
        verify(fallback, times(2)).chat(request);
        assertEquals(2, modelTurns.get());
        assertEquals(2, providerFailovers.get());
    }

    @Test
    void recoveredConfiguredPrimaryMustRemainFirstWithoutOnlinePromotion() {
        ChatModel primary = mock(ChatModel.class);
        ChatModel fallback = mock(ChatModel.class);
        ChatResponse fallbackResponse = mock(ChatResponse.class);
        ChatResponse primaryResponse = mock(ChatResponse.class);
        AtomicInteger modelTurns = new AtomicInteger();
        AtomicInteger providerFailovers = new AtomicInteger();
        when(primary.chat(request))
                .thenThrow(new RuntimeException("503 primary unavailable"))
                .thenReturn(primaryResponse);
        when(fallback.chat(request))
                .thenReturn(fallbackResponse)
                .thenThrow(new RuntimeException("503 fallback unavailable"));
        FailoverChatModel model = new FailoverChatModel(List.of(
                new AiModelCandidate<>("provider-a", "primary", primary),
                new AiModelCandidate<>("provider-b", "fallback", fallback)
        ), metrics, Duration.ofSeconds(5),
                modelTurns::incrementAndGet, providerFailovers::incrementAndGet);

        assertSame(fallbackResponse, model.chat(request));
        assertSame(primaryResponse, model.chat(request));

        verify(primary, times(2)).chat(request);
        verify(fallback, times(1)).chat(request);
        assertEquals(2, modelTurns.get());
        assertEquals(1, providerFailovers.get());
    }

    private FailoverChatModel model(ChatModel primary, ChatModel fallback) {
        return new FailoverChatModel(List.of(
                new AiModelCandidate<>("provider-a", "primary", primary),
                new AiModelCandidate<>("provider-b", "fallback", fallback)
        ), metrics);
    }

    private static void awaitIgnoringInterruption(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException ignored) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
