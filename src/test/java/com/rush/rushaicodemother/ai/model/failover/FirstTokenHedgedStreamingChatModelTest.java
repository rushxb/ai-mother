package com.rush.rushaicodemother.ai.model.failover;

import com.rush.rushaicodemother.core.error.GenerationErrorClassifier;
import com.rush.rushaicodemother.core.handler.GenerationCancellationAwareStreamingHandler;
import com.rush.rushaicodemother.core.handler.GenerationCancellationHandle;
import com.rush.rushaicodemother.monitor.AiModelMetricsCollector;
import com.rush.rushaicodemother.monitor.MonitorContext;
import com.rush.rushaicodemother.monitor.MonitorContextHolder;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class FirstTokenHedgedStreamingChatModelTest {

    private final ChatRequest request = mock(ChatRequest.class);
    private final AiModelMetricsCollector metrics = mock(AiModelMetricsCollector.class);

    @Test
    void primaryOutputBeforeDelayMustCancelTimerWithoutStartingHedge() {
        ManualHedgeScheduler scheduler = new ManualHedgeScheduler();
        ModelProbe primary = new ModelProbe();
        ModelProbe hedge = new ModelProbe();
        RecordingHandler downstream = new RecordingHandler();
        ChatResponse response = mock(ChatResponse.class);

        model(scheduler, primary, hedge).chat(request, downstream);
        primary.partial(0, "主请求输出");
        primary.complete(0, response);
        scheduler.fireNext();

        assertEquals(List.of("主请求输出"), downstream.partialResponses);
        assertSame(response, downstream.response);
        assertEquals(0, hedge.calls.get());
        assertEquals(1, scheduler.cancelledTasks());
        verify(metrics, never()).recordHedge(any(), any(), any(), any(), any());
    }

    @Test
    void hedgeOutputFirstMustCancelPrimaryAndIgnoreItsLateEvents() {
        ManualHedgeScheduler scheduler = new ManualHedgeScheduler();
        ModelProbe primary = new ModelProbe();
        ModelProbe hedge = new ModelProbe();
        RecordingHandler downstream = new RecordingHandler();
        ChatResponse response = mock(ChatResponse.class);

        model(scheduler, primary, hedge).chat(request, downstream);
        scheduler.fireNext();
        hedge.partial(0, "影子请求输出");
        primary.partial(0, "迟到的主请求输出");
        hedge.complete(0, response);

        assertEquals(List.of("影子请求输出"), downstream.partialResponses);
        assertSame(response, downstream.response);
        assertEquals(1, primary.cancellations.get());
        verifyHedgeMetric("started");
        verifyHedgeMetric("hedge_won");
    }

    @Test
    void primaryOutputAfterHedgeStartsMustCancelHedge() {
        ManualHedgeScheduler scheduler = new ManualHedgeScheduler();
        ModelProbe primary = new ModelProbe();
        ModelProbe hedge = new ModelProbe();
        RecordingHandler downstream = new RecordingHandler();
        ChatResponse response = mock(ChatResponse.class);

        model(scheduler, primary, hedge).chat(request, downstream);
        scheduler.fireNext();
        primary.partial(0, "主请求胜出");
        hedge.partial(0, "迟到的影子请求输出");
        primary.complete(0, response);

        assertEquals(List.of("主请求胜出"), downstream.partialResponses);
        assertSame(response, downstream.response);
        assertEquals(1, hedge.cancellations.get());
        verifyHedgeMetric("primary_won");
    }

    @Test
    void hedgeFailureMustNotTerminateAnActivePrimary() {
        ManualHedgeScheduler scheduler = new ManualHedgeScheduler();
        ModelProbe primary = new ModelProbe();
        ModelProbe hedge = new ModelProbe();
        RecordingHandler downstream = new RecordingHandler();
        ChatResponse response = mock(ChatResponse.class);

        model(scheduler, primary, hedge).chat(request, downstream);
        scheduler.fireNext();
        hedge.fail(0, new RuntimeException("503 影子请求不可用"));

        assertEquals(0, downstream.errors.size());
        primary.partial(0, "主请求恢复");
        primary.complete(0, response);

        assertEquals(List.of("主请求恢复"), downstream.partialResponses);
        assertSame(response, downstream.response);
        verifyHedgeMetric("primary_won");
    }

    @Test
    void primaryFailureWhileHedgeRunsMustNotStartThirdCandidate() {
        ManualHedgeScheduler scheduler = new ManualHedgeScheduler();
        ModelProbe primary = new ModelProbe();
        ModelProbe hedge = new ModelProbe();
        ModelProbe third = new ModelProbe();
        RecordingHandler downstream = new RecordingHandler();
        ChatResponse response = mock(ChatResponse.class);

        model(scheduler, primary, hedge, third).chat(request, downstream);
        scheduler.fireNext();
        primary.fail(0, new RuntimeException("503 主请求不可用"));

        assertEquals(0, third.calls.get());
        hedge.partial(0, "影子请求接管");
        hedge.complete(0, response);

        assertSame(response, downstream.response);
        assertEquals(0, third.calls.get());
        verifyHedgeMetric("hedge_won");
    }

    @Test
    void bothHedgedAttemptsFailingMustContinueSeriallyWithThirdCandidate() {
        ManualHedgeScheduler scheduler = new ManualHedgeScheduler();
        ModelProbe primary = new ModelProbe();
        ModelProbe hedge = new ModelProbe();
        ModelProbe third = new ModelProbe();
        RecordingHandler downstream = new RecordingHandler();
        ChatResponse response = mock(ChatResponse.class);

        model(scheduler, primary, hedge, third).chat(request, downstream);
        scheduler.fireNext();
        primary.fail(0, new RuntimeException("503 主请求不可用"));
        hedge.fail(0, new RuntimeException("503 影子请求不可用"));
        third.complete(0, response);

        assertEquals(1, third.calls.get());
        assertSame(response, downstream.response);
        assertEquals(0, downstream.errors.size());
        verifyHedgeMetric("failed");
        verify(metrics).recordFailover(
                "provider-b", "model-b", "provider-c", "model-c",
                GenerationErrorClassifier.CATEGORY_MODEL_UNAVAILABLE);
        verify(metrics, never()).recordHedge(
                "provider-a", "model-a", "provider-b", "model-b", "primary_won");
    }

    @Test
    void rejectedHedgeAdmissionMustLeavePrimaryRunning() {
        ManualHedgeScheduler scheduler = new ManualHedgeScheduler();
        ModelProbe primary = new ModelProbe();
        ModelProbe hedge = new ModelProbe();
        RecordingHandler downstream = new RecordingHandler();
        AtomicInteger modelTurns = new AtomicInteger();
        AtomicInteger failoverAdmissions = new AtomicInteger();
        RuntimeException admissionFailure = new RuntimeException("供应商故障转移预算耗尽");
        FirstTokenHedgePolicy policy = policy(scheduler, true);
        FailoverStreamingChatModel model = new FailoverStreamingChatModel(
                candidates(primary, hedge),
                metrics,
                modelTurns::incrementAndGet,
                () -> {
                    failoverAdmissions.incrementAndGet();
                    throw admissionFailure;
                },
                policy
        );
        ChatResponse response = mock(ChatResponse.class);

        model.chat(request, downstream);
        scheduler.fireNext();

        assertEquals(1, modelTurns.get());
        assertEquals(1, failoverAdmissions.get());
        assertEquals(0, hedge.calls.get());
        assertEquals(0, downstream.errors.size());
        primary.partial(0, "主请求继续运行");
        primary.complete(0, response);
        assertSame(response, downstream.response);
        verifyHedgeMetric("primary_won");
    }

    @Test
    void downstreamCancellationBeforeDelayMustCancelTimerAndPrimary() {
        ManualHedgeScheduler scheduler = new ManualHedgeScheduler();
        ModelProbe primary = new ModelProbe();
        ModelProbe hedge = new ModelProbe();
        RecordingHandler downstream = new RecordingHandler();

        model(scheduler, primary, hedge).chat(request, downstream);
        assertNotNull(downstream.cancellation.get());
        downstream.cancellation.get().cancel();
        scheduler.fireNext();

        assertEquals(1, scheduler.cancelledTasks());
        assertEquals(1, primary.cancellations.get());
        assertEquals(0, hedge.calls.get());
    }

    @Test
    void downstreamCancellationAfterHedgeStartsMustCancelBothRequests() {
        ManualHedgeScheduler scheduler = new ManualHedgeScheduler();
        ModelProbe primary = new ModelProbe();
        ModelProbe hedge = new ModelProbe();
        RecordingHandler downstream = new RecordingHandler();

        model(scheduler, primary, hedge).chat(request, downstream);
        scheduler.fireNext();
        downstream.cancellation.get().cancel();

        assertEquals(1, primary.cancellations.get());
        assertEquals(1, hedge.cancellations.get());
        verifyHedgeMetric("cancelled");
    }

    @Test
    void sameProviderMustKeepSerialPathWhenIsolationIsRequired() {
        ManualHedgeScheduler scheduler = new ManualHedgeScheduler();
        ModelProbe primary = new ModelProbe();
        ModelProbe fallback = new ModelProbe();
        RecordingHandler downstream = new RecordingHandler();
        ChatResponse response = mock(ChatResponse.class);
        FailoverStreamingChatModel model = new FailoverStreamingChatModel(
                List.of(
                        new AiModelCandidate<>("same-provider", "primary", primary.model),
                        new AiModelCandidate<>("same-provider", "fallback", fallback.model)
                ),
                metrics,
                () -> { },
                policy(scheduler, true)
        );

        model.chat(request, downstream);
        primary.partial(0, "串行主请求");
        primary.complete(0, response);

        assertEquals(0, scheduler.scheduledTasks());
        assertEquals(0, fallback.calls.get());
        assertSame(response, downstream.response);
    }

    @Test
    void failureAfterWinnerOutputMustNotSwitchToAnotherCandidate() {
        ManualHedgeScheduler scheduler = new ManualHedgeScheduler();
        ModelProbe primary = new ModelProbe();
        ModelProbe hedge = new ModelProbe();
        ModelProbe third = new ModelProbe();
        RecordingHandler downstream = new RecordingHandler();
        RuntimeException failure = new RuntimeException("503 输出后连接中断");

        model(scheduler, primary, hedge, third).chat(request, downstream);
        scheduler.fireNext();
        primary.partial(0, "已输出内容");
        primary.fail(0, failure);

        assertEquals(List.of("已输出内容"), downstream.partialResponses);
        assertEquals(List.of(failure), downstream.errors);
        assertEquals(0, third.calls.get());
        assertEquals(1, hedge.cancellations.get());
        verifyHedgeMetric("primary_won");
    }

    @Test
    void hedgeWinnerMustNotChangeThePublishedCandidateOrderForTheNextTurn() {
        ManualHedgeScheduler scheduler = new ManualHedgeScheduler();
        ModelProbe primary = new ModelProbe();
        ModelProbe hedge = new ModelProbe();
        AtomicInteger modelTurns = new AtomicInteger();
        AtomicInteger failoverAdmissions = new AtomicInteger();
        FailoverStreamingChatModel model = new FailoverStreamingChatModel(
                candidates(primary, hedge),
                metrics,
                modelTurns::incrementAndGet,
                failoverAdmissions::incrementAndGet,
                policy(scheduler, true)
        );
        RecordingHandler firstTurn = new RecordingHandler();
        RecordingHandler secondTurn = new RecordingHandler();

        model.chat(request, firstTurn);
        scheduler.fireNext();
        hedge.partial(0, "影子请求胜出");
        hedge.complete(0, mock(ChatResponse.class));
        model.chat(request, secondTurn);

        assertEquals(2, primary.calls.get());
        assertEquals(1, hedge.calls.get());
        assertEquals(2, modelTurns.get());
        assertEquals(1, failoverAdmissions.get());
        secondTurn.cancellation.get().cancel();
    }

    @Test
    void hedgeLaunchMustRestoreCapturedMonitorContextAcrossThreadBoundary() {
        ManualHedgeScheduler scheduler = new ManualHedgeScheduler();
        ModelProbe primary = new ModelProbe();
        ModelProbe hedge = new ModelProbe();
        RecordingHandler downstream = new RecordingHandler();
        MonitorContext captured = MonitorContext.builder()
                .userId("user-1")
                .appId("app-1")
                .taskId("task-1")
                .build();
        try {
            MonitorContextHolder.setContext(captured);
            model(scheduler, primary, hedge).chat(request, downstream);
            MonitorContextHolder.clearContext();

            scheduler.fireNext();

            assertEquals("task-1", hedge.monitorContexts.getFirst().getTaskId());
            assertNull(MonitorContextHolder.getContext());
        } finally {
            MonitorContextHolder.clearContext();
        }
    }

    private FailoverStreamingChatModel model(ManualHedgeScheduler scheduler,
                                             ModelProbe... probes) {
        return new FailoverStreamingChatModel(
                candidates(probes),
                metrics,
                () -> { },
                policy(scheduler, true)
        );
    }

    private List<AiModelCandidate<StreamingChatModel>> candidates(ModelProbe... probes) {
        List<AiModelCandidate<StreamingChatModel>> candidates = new ArrayList<>();
        for (int index = 0; index < probes.length; index++) {
            char suffix = (char) ('a' + index);
            candidates.add(new AiModelCandidate<>(
                    "provider-" + suffix,
                    "model-" + suffix,
                    probes[index].model
            ));
        }
        return List.copyOf(candidates);
    }

    private FirstTokenHedgePolicy policy(ManualHedgeScheduler scheduler,
                                         boolean requireDistinctProvider) {
        return new FirstTokenHedgePolicy(
                true,
                Duration.ofSeconds(1),
                requireDistinctProvider,
                scheduler
        );
    }

    private void verifyHedgeMetric(String outcome) {
        verify(metrics).recordHedge(
                "provider-a", "model-a", "provider-b", "model-b", outcome);
    }

    private static final class ModelProbe {
        private final StreamingChatModel model = mock(StreamingChatModel.class);
        private final List<StreamingChatResponseHandler> handlers = new ArrayList<>();
        private final List<MonitorContext> monitorContexts = new ArrayList<>();
        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicInteger cancellations = new AtomicInteger();

        private ModelProbe() {
            doAnswer(invocation -> {
                StreamingChatResponseHandler handler = invocation.getArgument(1);
                handlers.add(handler);
                monitorContexts.add(MonitorContextHolder.getContext());
                calls.incrementAndGet();
                ((GenerationCancellationAwareStreamingHandler) handler)
                        .registerCancellationHandle(cancellations::incrementAndGet);
                return null;
            }).when(model).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
        }

        private void partial(int callIndex, String text) {
            handler(callIndex).onPartialResponse(text);
        }

        private void complete(int callIndex, ChatResponse response) {
            handler(callIndex).onCompleteResponse(response);
        }

        private void fail(int callIndex, Throwable failure) {
            handler(callIndex).onError(failure);
        }

        private StreamingChatResponseHandler handler(int callIndex) {
            return handlers.get(callIndex);
        }
    }

    private static final class ManualHedgeScheduler implements FirstTokenHedgeScheduler {
        private final List<ScheduledTask> tasks = new ArrayList<>();
        private int nextTask;

        @Override
        public GenerationCancellationHandle schedule(Duration delay, Runnable task) {
            ScheduledTask scheduledTask = new ScheduledTask(task);
            tasks.add(scheduledTask);
            return scheduledTask::cancel;
        }

        private void fireNext() {
            if (nextTask >= tasks.size()) {
                return;
            }
            tasks.get(nextTask++).fire();
        }

        private int scheduledTasks() {
            return tasks.size();
        }

        private int cancelledTasks() {
            return (int) tasks.stream().filter(ScheduledTask::cancelled).count();
        }
    }

    private static final class ScheduledTask {
        private final Runnable task;
        private boolean cancelled;
        private boolean fired;

        private ScheduledTask(Runnable task) {
            this.task = task;
        }

        private void cancel() {
            cancelled = true;
        }

        private void fire() {
            if (cancelled || fired) {
                return;
            }
            fired = true;
            task.run();
        }

        private boolean cancelled() {
            return cancelled;
        }
    }

    private static final class RecordingHandler implements GenerationCancellationAwareStreamingHandler {
        private final List<String> partialResponses = new ArrayList<>();
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
        public void onCompleteResponse(ChatResponse completeResponse) {
            response = completeResponse;
        }

        @Override
        public void onError(Throwable error) {
            errors.add(error);
        }
    }
}
