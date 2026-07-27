package com.rush.rushaicodemother.ai.model.failover;

import com.rush.rushaicodemother.core.error.GenerationErrorClassifier;
import com.rush.rushaicodemother.core.handler.GenerationCancellationAwareStreamingHandler;
import com.rush.rushaicodemother.core.handler.GenerationCancellationHandle;
import com.rush.rushaicodemother.monitor.AiModelMetricsCollector;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.PartialToolCall;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class FailoverStreamingChatModelTest {

    private final ChatRequest request = mock(ChatRequest.class);
    private final AiModelMetricsCollector metrics = mock(AiModelMetricsCollector.class);

    @Test
    void failureBeforeFirstOutputMustFailOverAndIgnoreLatePrimaryEvents() {
        StreamingChatModel primary = mock(StreamingChatModel.class);
        StreamingChatModel fallback = mock(StreamingChatModel.class);
        RuntimeException first = new RuntimeException("503 primary unavailable");
        ChatResponse response = mock(ChatResponse.class);
        AtomicReference<StreamingChatResponseHandler> primaryHandler = new AtomicReference<>();
        doAnswer(invocation -> {
            StreamingChatResponseHandler handler = invocation.getArgument(1);
            primaryHandler.set(handler);
            handler.onError(first);
            return null;
        }).when(primary).chat(eq(request), any(StreamingChatResponseHandler.class));
        doAnswer(invocation -> {
            StreamingChatResponseHandler handler = invocation.getArgument(1);
            handler.onPartialResponse("fallback-output");
            handler.onCompleteResponse(response);
            return null;
        }).when(fallback).chat(eq(request), any(StreamingChatResponseHandler.class));
        RecordingHandler downstream = new RecordingHandler();

        model(primary, fallback).chat(request, downstream);
        primaryHandler.get().onPartialResponse("late-primary-output");

        assertEquals(List.of("fallback-output"), downstream.partialResponses);
        assertSame(response, downstream.response);
        assertEquals(0, downstream.errors.size());
        verify(metrics).recordFailover(
                "provider-a", "primary", "provider-b", "fallback",
                GenerationErrorClassifier.CATEGORY_MODEL_UNAVAILABLE);
    }

    @Test
    void synchronousFailureBeforeFirstOutputMustFailOver() {
        StreamingChatModel primary = mock(StreamingChatModel.class);
        StreamingChatModel fallback = mock(StreamingChatModel.class);
        ChatResponse response = mock(ChatResponse.class);
        doAnswer(invocation -> {
            throw new RuntimeException("read timed out");
        }).when(primary).chat(eq(request), any(StreamingChatResponseHandler.class));
        doAnswer(invocation -> {
            invocation.<StreamingChatResponseHandler>getArgument(1).onCompleteResponse(response);
            return null;
        }).when(fallback).chat(eq(request), any(StreamingChatResponseHandler.class));
        RecordingHandler downstream = new RecordingHandler();

        model(primary, fallback).chat(request, downstream);

        assertSame(response, downstream.response);
        assertEquals(0, downstream.errors.size());
    }

    @Test
    void partialTextMustDisableFailover() {
        StreamingChatModel primary = mock(StreamingChatModel.class);
        StreamingChatModel fallback = mock(StreamingChatModel.class);
        RuntimeException failure = new RuntimeException("503 after output");
        doAnswer(invocation -> {
            StreamingChatResponseHandler handler = invocation.getArgument(1);
            handler.onPartialResponse("already-visible");
            handler.onError(failure);
            return null;
        }).when(primary).chat(eq(request), any(StreamingChatResponseHandler.class));
        RecordingHandler downstream = new RecordingHandler();

        model(primary, fallback).chat(request, downstream);

        assertEquals(List.of("already-visible"), downstream.partialResponses);
        assertEquals(List.of(failure), downstream.errors);
        verifyNoInteractions(fallback, metrics);
    }

    @Test
    void partialToolCallMustDisableFailover() {
        StreamingChatModel primary = mock(StreamingChatModel.class);
        StreamingChatModel fallback = mock(StreamingChatModel.class);
        PartialToolCall toolCall = mock(PartialToolCall.class);
        RuntimeException failure = new RuntimeException("503 after tool fragment");
        doAnswer(invocation -> {
            StreamingChatResponseHandler handler = invocation.getArgument(1);
            handler.onPartialToolCall(toolCall);
            handler.onError(failure);
            return null;
        }).when(primary).chat(eq(request), any(StreamingChatResponseHandler.class));
        RecordingHandler downstream = new RecordingHandler();

        model(primary, fallback).chat(request, downstream);

        assertEquals(List.of(toolCall), downstream.partialToolCalls);
        assertEquals(List.of(failure), downstream.errors);
        verifyNoInteractions(fallback, metrics);
    }

    @Test
    void authenticationFailureMustNotFailOver() {
        StreamingChatModel primary = mock(StreamingChatModel.class);
        StreamingChatModel fallback = mock(StreamingChatModel.class);
        RuntimeException failure = new RuntimeException("401 Unauthorized");
        doAnswer(invocation -> {
            invocation.<StreamingChatResponseHandler>getArgument(1).onError(failure);
            return null;
        }).when(primary).chat(eq(request), any(StreamingChatResponseHandler.class));
        RecordingHandler downstream = new RecordingHandler();

        model(primary, fallback).chat(request, downstream);

        assertEquals(List.of(failure), downstream.errors);
        verifyNoInteractions(fallback, metrics);
    }

    @Test
    void allCandidatesFailingMustEmitExactlyOneTerminalError() {
        StreamingChatModel primary = mock(StreamingChatModel.class);
        StreamingChatModel fallback = mock(StreamingChatModel.class);
        RuntimeException first = new RuntimeException("503 primary unavailable");
        RuntimeException terminal = new RuntimeException("503 fallback unavailable");
        doAnswer(invocation -> {
            invocation.<StreamingChatResponseHandler>getArgument(1).onError(first);
            return null;
        }).when(primary).chat(eq(request), any(StreamingChatResponseHandler.class));
        doAnswer(invocation -> {
            StreamingChatResponseHandler handler = invocation.getArgument(1);
            handler.onError(terminal);
            handler.onError(new RuntimeException("late duplicate"));
            handler.onCompleteResponse(mock(ChatResponse.class));
            return null;
        }).when(fallback).chat(eq(request), any(StreamingChatResponseHandler.class));
        RecordingHandler downstream = new RecordingHandler();

        model(primary, fallback).chat(request, downstream);

        assertEquals(List.of(terminal), downstream.errors);
        assertEquals(List.of(first), List.of(terminal.getSuppressed()));
        assertEquals(null, downstream.response);
    }

    @Test
    void providerAttemptReservationMustRunForEveryFailoverCandidate() {
        StreamingChatModel primary = mock(StreamingChatModel.class);
        StreamingChatModel fallback = mock(StreamingChatModel.class);
        ChatResponse response = mock(ChatResponse.class);
        AtomicInteger providerAttempts = new AtomicInteger();
        doAnswer(invocation -> {
            invocation.<StreamingChatResponseHandler>getArgument(1)
                    .onError(new RuntimeException("503 primary unavailable"));
            return null;
        }).when(primary).chat(eq(request), any(StreamingChatResponseHandler.class));
        doAnswer(invocation -> {
            invocation.<StreamingChatResponseHandler>getArgument(1).onCompleteResponse(response);
            return null;
        }).when(fallback).chat(eq(request), any(StreamingChatResponseHandler.class));
        RecordingHandler downstream = new RecordingHandler();

        model(primary, fallback, providerAttempts::incrementAndGet).chat(request, downstream);

        assertEquals(2, providerAttempts.get());
        assertSame(response, downstream.response);
    }

    @Test
    void modelTurnAndProviderFailoverMustUseSeparateAdmissions() {
        StreamingChatModel primary = mock(StreamingChatModel.class);
        StreamingChatModel fallback = mock(StreamingChatModel.class);
        ChatResponse response = mock(ChatResponse.class);
        AtomicInteger modelTurns = new AtomicInteger();
        AtomicInteger providerFailovers = new AtomicInteger();
        doAnswer(invocation -> {
            invocation.<StreamingChatResponseHandler>getArgument(1)
                    .onError(new RuntimeException("503 primary unavailable"));
            return null;
        }).when(primary).chat(eq(request), any(StreamingChatResponseHandler.class));
        doAnswer(invocation -> {
            invocation.<StreamingChatResponseHandler>getArgument(1).onCompleteResponse(response);
            return null;
        }).when(fallback).chat(eq(request), any(StreamingChatResponseHandler.class));
        RecordingHandler downstream = new RecordingHandler();
        FailoverStreamingChatModel model = new FailoverStreamingChatModel(List.of(
                new AiModelCandidate<>("provider-a", "primary", primary),
                new AiModelCandidate<>("provider-b", "fallback", fallback)
        ), metrics, modelTurns::incrementAndGet, providerFailovers::incrementAndGet);

        model.chat(request, downstream);

        assertEquals(1, modelTurns.get());
        assertEquals(1, providerFailovers.get());
        assertSame(response, downstream.response);
    }

    @Test
    void exhaustedProviderAttemptBudgetMustPreventTheFallbackRequest() {
        StreamingChatModel primary = mock(StreamingChatModel.class);
        StreamingChatModel fallback = mock(StreamingChatModel.class);
        AtomicInteger providerAttempts = new AtomicInteger();
        RuntimeException budgetFailure = new RuntimeException("provider attempt budget exhausted");
        doAnswer(invocation -> {
            invocation.<StreamingChatResponseHandler>getArgument(1)
                    .onError(new RuntimeException("503 primary unavailable"));
            return null;
        }).when(primary).chat(eq(request), any(StreamingChatResponseHandler.class));
        RecordingHandler downstream = new RecordingHandler();
        Runnable reservation = () -> {
            if (providerAttempts.incrementAndGet() > 1) {
                throw budgetFailure;
            }
        };

        model(primary, fallback, reservation).chat(request, downstream);

        assertEquals(2, providerAttempts.get());
        assertEquals(List.of(budgetFailure), downstream.errors);
        verifyNoInteractions(fallback);
    }

    @Test
    void taskScopedStreamingPoolMustStartTheNextTurnFromTheSuccessfulFallback() {
        StreamingChatModel primary = mock(StreamingChatModel.class);
        StreamingChatModel fallback = mock(StreamingChatModel.class);
        ChatResponse first = mock(ChatResponse.class);
        ChatResponse second = mock(ChatResponse.class);
        AtomicInteger modelTurns = new AtomicInteger();
        AtomicInteger providerFailovers = new AtomicInteger();
        doAnswer(invocation -> {
            invocation.<StreamingChatResponseHandler>getArgument(1)
                    .onError(new RuntimeException("503 primary unavailable"));
            return null;
        }).when(primary).chat(eq(request), any(StreamingChatResponseHandler.class));
        AtomicInteger fallbackCalls = new AtomicInteger();
        doAnswer(invocation -> {
            ChatResponse response = fallbackCalls.getAndIncrement() == 0 ? first : second;
            invocation.<StreamingChatResponseHandler>getArgument(1).onCompleteResponse(response);
            return null;
        }).when(fallback).chat(eq(request), any(StreamingChatResponseHandler.class));
        FailoverStreamingChatModel model = new FailoverStreamingChatModel(List.of(
                new AiModelCandidate<>("provider-a", "primary", primary),
                new AiModelCandidate<>("provider-b", "fallback", fallback)
        ), metrics, modelTurns::incrementAndGet, providerFailovers::incrementAndGet);
        RecordingHandler firstTurn = new RecordingHandler();
        RecordingHandler secondTurn = new RecordingHandler();

        model.chat(request, firstTurn);
        model.chat(request, secondTurn);

        assertSame(first, firstTurn.response);
        assertSame(second, secondTurn.response);
        verify(primary, times(1)).chat(eq(request), any(StreamingChatResponseHandler.class));
        verify(fallback, times(2)).chat(eq(request), any(StreamingChatResponseHandler.class));
        assertEquals(2, modelTurns.get());
        assertEquals(1, providerFailovers.get());
    }

    @Test
    void failedStickyStreamingFallbackMustWrapToARecoveredPrimary() {
        StreamingChatModel primary = mock(StreamingChatModel.class);
        StreamingChatModel fallback = mock(StreamingChatModel.class);
        ChatResponse fallbackResponse = mock(ChatResponse.class);
        ChatResponse primaryResponse = mock(ChatResponse.class);
        AtomicInteger modelTurns = new AtomicInteger();
        AtomicInteger providerFailovers = new AtomicInteger();
        AtomicInteger primaryCalls = new AtomicInteger();
        AtomicInteger fallbackCalls = new AtomicInteger();
        doAnswer(invocation -> {
            StreamingChatResponseHandler handler = invocation.getArgument(1);
            if (primaryCalls.getAndIncrement() == 0) {
                handler.onError(new RuntimeException("503 primary unavailable"));
            } else {
                handler.onCompleteResponse(primaryResponse);
            }
            return null;
        }).when(primary).chat(eq(request), any(StreamingChatResponseHandler.class));
        doAnswer(invocation -> {
            StreamingChatResponseHandler handler = invocation.getArgument(1);
            if (fallbackCalls.getAndIncrement() == 0) {
                handler.onCompleteResponse(fallbackResponse);
            } else {
                handler.onError(new RuntimeException("503 fallback unavailable"));
            }
            return null;
        }).when(fallback).chat(eq(request), any(StreamingChatResponseHandler.class));
        FailoverStreamingChatModel model = new FailoverStreamingChatModel(List.of(
                new AiModelCandidate<>("provider-a", "primary", primary),
                new AiModelCandidate<>("provider-b", "fallback", fallback)
        ), metrics, modelTurns::incrementAndGet, providerFailovers::incrementAndGet);
        RecordingHandler firstTurn = new RecordingHandler();
        RecordingHandler secondTurn = new RecordingHandler();

        model.chat(request, firstTurn);
        model.chat(request, secondTurn);

        assertSame(fallbackResponse, firstTurn.response);
        assertSame(primaryResponse, secondTurn.response);
        verify(primary, times(2)).chat(eq(request), any(StreamingChatResponseHandler.class));
        verify(fallback, times(2)).chat(eq(request), any(StreamingChatResponseHandler.class));
        assertEquals(2, modelTurns.get());
        assertEquals(2, providerFailovers.get());
    }

    @Test
    void cancellationRegistrationMustReachTheActiveProviderBeforeAnyOutput() {
        StreamingChatModel primary = mock(StreamingChatModel.class);
        StreamingChatModel fallback = mock(StreamingChatModel.class);
        AtomicInteger providerCancellations = new AtomicInteger();
        doAnswer(invocation -> {
            StreamingChatResponseHandler handler = invocation.getArgument(1);
            if (handler instanceof GenerationCancellationAwareStreamingHandler awareHandler) {
                awareHandler.registerCancellationHandle(providerCancellations::incrementAndGet);
            }
            return null;
        }).when(primary).chat(eq(request), any(StreamingChatResponseHandler.class));
        RecordingHandler downstream = new RecordingHandler();

        model(primary, fallback).chat(request, downstream);

        assertNotNull(downstream.cancellation.get());
        downstream.cancellation.get().cancel();
        assertEquals(1, providerCancellations.get());
        verifyNoInteractions(fallback);
    }

    @Test
    void lateCancellationFromFailedCandidateMustNotReplaceTheActiveCandidate() {
        StreamingChatModel primary = mock(StreamingChatModel.class);
        StreamingChatModel fallback = mock(StreamingChatModel.class);
        AtomicReference<GenerationCancellationAwareStreamingHandler> primaryHandler =
                new AtomicReference<>();
        AtomicInteger latePrimaryCancellations = new AtomicInteger();
        AtomicInteger fallbackCancellations = new AtomicInteger();
        doAnswer(invocation -> {
            StreamingChatResponseHandler handler = invocation.getArgument(1);
            primaryHandler.set((GenerationCancellationAwareStreamingHandler) handler);
            handler.onError(new RuntimeException("503 primary unavailable"));
            return null;
        }).when(primary).chat(eq(request), any(StreamingChatResponseHandler.class));
        doAnswer(invocation -> {
            StreamingChatResponseHandler handler = invocation.getArgument(1);
            ((GenerationCancellationAwareStreamingHandler) handler)
                    .registerCancellationHandle(fallbackCancellations::incrementAndGet);
            return null;
        }).when(fallback).chat(eq(request), any(StreamingChatResponseHandler.class));
        RecordingHandler downstream = new RecordingHandler();

        model(primary, fallback).chat(request, downstream);
        primaryHandler.get().registerCancellationHandle(latePrimaryCancellations::incrementAndGet);
        downstream.cancellation.get().cancel();

        assertEquals(1, latePrimaryCancellations.get());
        assertEquals(1, fallbackCancellations.get());
    }

    private FailoverStreamingChatModel model(StreamingChatModel primary,
                                             StreamingChatModel fallback) {
        return model(primary, fallback, () -> { });
    }

    private FailoverStreamingChatModel model(StreamingChatModel primary,
                                             StreamingChatModel fallback,
                                             Runnable beforeProviderAttempt) {
        return new FailoverStreamingChatModel(List.of(
                new AiModelCandidate<>("provider-a", "primary", primary),
                new AiModelCandidate<>("provider-b", "fallback", fallback)
        ), metrics, beforeProviderAttempt);
    }

    private static final class RecordingHandler implements GenerationCancellationAwareStreamingHandler {
        private final List<String> partialResponses = new ArrayList<>();
        private final List<PartialToolCall> partialToolCalls = new ArrayList<>();
        private final List<Throwable> errors = new ArrayList<>();
        private final AtomicReference<GenerationCancellationHandle> cancellation = new AtomicReference<>();
        private ChatResponse response;

        @Override
        public void registerCancellationHandle(GenerationCancellationHandle cancellationHandle) {
            cancellation.set(cancellationHandle);
        }

        @Override
        public void onPartialResponse(String partialResponse) {
            partialResponses.add(partialResponse);
        }

        @Override
        public void onPartialToolCall(PartialToolCall partialToolCall) {
            partialToolCalls.add(partialToolCall);
        }

        @Override
        public void onCompleteResponse(ChatResponse completeResponse) {
            response = completeResponse;
        }

        @Override
        public void onError(Throwable error) {
            errors.add(error);
        }
    }
}
