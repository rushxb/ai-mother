package com.rush.rushaicodemother.ai.model.failover;

import com.rush.rushaicodemother.core.handler.GenerationCancellationAwareStreamingHandler;
import com.rush.rushaicodemother.core.handler.GenerationCancellationHandle;
import com.rush.rushaicodemother.infrastructure.diagnostic.LogExceptionSanitizer;
import com.rush.rushaicodemother.monitor.AiModelMetricsCollector;
import com.rush.rushaicodemother.monitor.MonitorContext;
import com.rush.rushaicodemother.monitor.MonitorContextHolder;
import com.rush.rushaicodemother.orchestration.runtime.model.GenerationModelCancellationScope;
import com.rush.rushaicodemother.orchestration.runtime.model.GenerationModelInvocationCancellationBridge;
import dev.langchain4j.model.chat.ChatRequestOptions;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.CompleteToolCall;
import dev.langchain4j.model.chat.response.PartialResponse;
import dev.langchain4j.model.chat.response.PartialResponseContext;
import dev.langchain4j.model.chat.response.PartialThinking;
import dev.langchain4j.model.chat.response.PartialThinkingContext;
import dev.langchain4j.model.chat.response.PartialToolCall;
import dev.langchain4j.model.chat.response.PartialToolCallContext;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.IntConsumer;

/** 单次流式调用的首 Token 对冲协调器。 */
@Slf4j
final class HedgedStreamingExecution implements GenerationCancellationHandle {

    private final Object stateLock = new Object();
    private final List<AiModelCandidate<StreamingChatModel>> candidates;
    private final List<Integer> candidateOrder;
    private final AiModelMetricsCollector metrics;
    private final IntConsumer beforeProviderAttempt;
    private final IntConsumer successfulCandidate;
    private final FirstTokenHedgePolicy policy;
    private final ChatRequest request;
    private final ChatRequestOptions options;
    private final StreamingChatResponseHandler downstream;
    private final GenerationModelInvocationCancellationBridge cancellationBridge;
    private final GenerationModelCancellationScope cancellationScope;
    private final List<Attempt> attempts = new ArrayList<>();
    private final Set<Integer> startedPositions = new HashSet<>();
    private final List<FailureRecord> failures = new ArrayList<>();

    private GenerationCancellationHandle hedgeTimer;
    private Attempt winner;
    private int activeAttempts;
    private boolean hedgeStarted;
    private boolean hedgeOutcomeRecorded;
    private boolean terminal;

    HedgedStreamingExecution(
            List<AiModelCandidate<StreamingChatModel>> candidates,
            List<Integer> candidateOrder,
            AiModelMetricsCollector metrics,
            IntConsumer beforeProviderAttempt,
            IntConsumer successfulCandidate,
            FirstTokenHedgePolicy policy,
            ChatRequest request,
            ChatRequestOptions options,
            StreamingChatResponseHandler downstream,
            GenerationModelInvocationCancellationBridge cancellationBridge,
            GenerationModelCancellationScope cancellationScope
    ) {
        this.candidates = candidates;
        this.candidateOrder = candidateOrder;
        this.metrics = metrics;
        this.beforeProviderAttempt = beforeProviderAttempt;
        this.successfulCandidate = successfulCandidate;
        this.policy = policy;
        this.request = request;
        this.options = options;
        this.downstream = downstream;
        this.cancellationBridge = cancellationBridge;
        this.cancellationScope = cancellationScope;
    }

    void start() {
        GenerationCancellationAwareStreamingHandler.registerIfSupported(downstream, this);
        Attempt primary;
        synchronized (stateLock) {
            if (terminal) {
                return;
            }
            primary = reserveAttemptLocked(0, false);
        }
        scheduleHedge();
        startAttempt(primary);
    }

    private void scheduleHedge() {
        GenerationCancellationHandle scheduled;
        try {
            scheduled = policy.schedule(withMonitorContext(this::launchHedge));
        } catch (RuntimeException schedulingFailure) {
            log.warn("首 Token 对冲延迟任务创建失败，将继续使用主请求: {}",
                    LogExceptionSanitizer.sanitizeMessage(schedulingFailure));
            return;
        }
        boolean cancelImmediately;
        synchronized (stateLock) {
            cancelImmediately = terminal || winner != null || startedPositions.contains(1);
            if (!cancelImmediately) {
                hedgeTimer = scheduled;
            }
        }
        if (cancelImmediately) {
            safeCancel(scheduled, "取消已失效的首 Token 对冲计时器");
        }
    }

    private Runnable withMonitorContext(Runnable task) {
        MonitorContext current = MonitorContextHolder.getContext();
        if (current == null) {
            return task;
        }
        MonitorContext captured = MonitorContext.builder()
                .userId(current.getUserId())
                .appId(current.getAppId())
                .taskId(current.getTaskId())
                .build();
        return () -> {
            MonitorContext previous = MonitorContextHolder.getContext();
            try {
                MonitorContextHolder.setContext(captured);
                task.run();
            } finally {
                if (previous == null) {
                    MonitorContextHolder.clearContext();
                } else {
                    MonitorContextHolder.setContext(previous);
                }
            }
        };
    }

    private void launchHedge() {
        Attempt shadow;
        synchronized (stateLock) {
            hedgeTimer = null;
            if (terminal || winner != null || activeAttempts != 1 || startedPositions.contains(1)) {
                return;
            }
            shadow = reserveAttemptLocked(1, true);
            hedgeStarted = true;
        }
        recordHedge("started");
        startAttempt(shadow);
    }

    private void startAttempt(Attempt attempt) {
        if (attempt == null || !attempt.canStart()) {
            return;
        }
        AiModelCandidate<StreamingChatModel> candidate = candidate(attempt.orderPosition());
        StreamingChatResponseHandler forwarding = forwardingHandler(attempt);
        try {
            beforeProviderAttempt.accept(attempt.orderPosition());
            if (!attempt.canStart()) {
                return;
            }
            invokeCandidate(candidate, forwarding);
        } catch (RuntimeException synchronousFailure) {
            attempt.fail(synchronousFailure);
        }
    }

    private void invokeCandidate(AiModelCandidate<StreamingChatModel> candidate,
                                 StreamingChatResponseHandler forwarding) {
        if (cancellationBridge == null) {
            invokeCandidateDirectly(candidate, forwarding);
            return;
        }
        try (GenerationModelInvocationCancellationBridge.ScopeBinding ignored =
                     cancellationBridge.activate(cancellationScope)) {
            invokeCandidateDirectly(candidate, forwarding);
        }
    }

    private void invokeCandidateDirectly(AiModelCandidate<StreamingChatModel> candidate,
                                         StreamingChatResponseHandler forwarding) {
        if (options == null) {
            candidate.model().chat(request, forwarding);
        } else {
            candidate.model().chat(request, options, forwarding);
        }
    }

    private StreamingChatResponseHandler forwardingHandler(Attempt attempt) {
        return new GenerationCancellationAwareStreamingHandler() {
            @Override
            public void registerCancellationHandle(GenerationCancellationHandle cancellationHandle) {
                attempt.registerCancellationHandle(cancellationHandle);
            }

            @Override
            public void onPartialResponse(String partialResponse) {
                attempt.forward(() -> downstream.onPartialResponse(partialResponse));
            }

            @Override
            public void onPartialResponse(PartialResponse partialResponse,
                                          PartialResponseContext context) {
                attempt.forward(() -> downstream.onPartialResponse(partialResponse, context));
            }

            @Override
            public void onPartialThinking(PartialThinking partialThinking) {
                attempt.forward(() -> downstream.onPartialThinking(partialThinking));
            }

            @Override
            public void onPartialThinking(PartialThinking partialThinking,
                                          PartialThinkingContext context) {
                attempt.forward(() -> downstream.onPartialThinking(partialThinking, context));
            }

            @Override
            public void onPartialToolCall(PartialToolCall partialToolCall) {
                attempt.forward(() -> downstream.onPartialToolCall(partialToolCall));
            }

            @Override
            public void onPartialToolCall(PartialToolCall partialToolCall,
                                          PartialToolCallContext context) {
                attempt.forward(() -> downstream.onPartialToolCall(partialToolCall, context));
            }

            @Override
            public void onCompleteToolCall(CompleteToolCall completeToolCall) {
                attempt.forward(() -> downstream.onCompleteToolCall(completeToolCall));
            }

            @Override
            public void onUnmappedRawEvent(Object event) {
                attempt.forward(() -> downstream.onUnmappedRawEvent(event));
            }

            @Override
            public void onCompleteResponse(ChatResponse completeResponse) {
                attempt.complete(completeResponse);
            }

            @Override
            public void onError(Throwable error) {
                attempt.fail(error == null
                        ? new IllegalStateException("模型流失败但未提供异常")
                        : error);
            }
        };
    }

    private boolean claimWinner(Attempt attempt) {
        List<Attempt> losers;
        GenerationCancellationHandle timer;
        String hedgeOutcome = null;
        synchronized (stateLock) {
            if (terminal) {
                return false;
            }
            if (winner != null) {
                return winner == attempt;
            }
            winner = attempt;
            timer = clearHedgeTimerLocked();
            losers = attempts.stream().filter(current -> current != attempt).toList();
            if (hedgeStarted && !hedgeOutcomeRecorded) {
                hedgeOutcomeRecorded = true;
                hedgeOutcome = attempt.speculative() ? "hedge_won" : "primary_won";
            }
        }
        safeCancel(timer, "取消首 Token 对冲计时器");
        cancelAttempts(losers);
        if (hedgeOutcome != null) {
            recordHedge(hedgeOutcome);
        }
        return true;
    }

    private void complete(Attempt attempt, ChatResponse response) {
        boolean claimed = claimWinner(attempt);
        synchronized (stateLock) {
            activeAttempts = Math.max(0, activeAttempts - 1);
            if (!claimed || terminal || winner != attempt) {
                return;
            }
            terminal = true;
        }
        successfulCandidate.accept(attempt.candidateIndex());
        downstream.onCompleteResponse(response);
    }

    private void fail(Attempt attempt, boolean outputObserved, Throwable failure) {
        Attempt nextAttempt = null;
        FailureTerminal terminalAction = null;
        GenerationCancellationHandle timerToCancel = null;
        FailoverTransition failoverTransition = null;
        String hedgeOutcome = null;
        synchronized (stateLock) {
            activeAttempts = Math.max(0, activeAttempts - 1);
            AiModelFailoverPolicy.Decision decision = AiModelFailoverPolicy.classify(failure);
            failures.add(new FailureRecord(attempt, failure, decision));
            if (terminal) {
                return;
            }
            if (winner != null) {
                if (winner != attempt) {
                    return;
                }
                terminalAction = terminateLocked(failure);
            } else if ((!decision.recoverable() || outputObserved) && !attempt.speculative()) {
                terminalAction = terminateLocked(failure);
            } else if (activeAttempts == 0) {
                FailureRecord nonRecoverable = firstNonRecoverableFailureLocked();
                if (nonRecoverable != null) {
                    terminalAction = terminateLocked(nonRecoverable.failure());
                } else {
                    int nextPosition = nextUnstartedPositionLocked();
                    if (nextPosition < 0) {
                        terminalAction = terminateLocked(failure);
                    } else {
                        nextAttempt = reserveAttemptLocked(nextPosition, false);
                        timerToCancel = clearHedgeTimerLocked();
                        if (hedgeStarted && !hedgeOutcomeRecorded) {
                            hedgeOutcomeRecorded = true;
                            hedgeOutcome = "failed";
                        }
                        failoverTransition = new FailoverTransition(
                                candidate(attempt.orderPosition()),
                                candidate(nextPosition),
                                decision.category()
                        );
                    }
                }
            }
        }

        safeCancel(timerToCancel, "取消已转为串行故障切换的对冲计时器");
        if (terminalAction != null) {
            finishTerminalFailure(terminalAction);
            return;
        }
        if (failoverTransition != null) {
            recordFailover(failoverTransition);
        }
        if (hedgeOutcome != null) {
            recordHedge(hedgeOutcome);
        }
        if (nextAttempt != null) {
            startAttempt(nextAttempt);
        }
    }

    private FailureTerminal terminateLocked(Throwable failure) {
        terminal = true;
        GenerationCancellationHandle timer = clearHedgeTimerLocked();
        List<Attempt> active = attempts.stream().filter(Attempt::canStart).toList();
        String hedgeOutcome = null;
        if (hedgeStarted && !hedgeOutcomeRecorded) {
            hedgeOutcomeRecorded = true;
            hedgeOutcome = "failed";
        }
        return new FailureTerminal(withSuppressed(failure), timer, active, hedgeOutcome);
    }

    private void finishTerminalFailure(FailureTerminal action) {
        safeCancel(action.timer(), "取消首 Token 对冲计时器");
        cancelAttempts(action.activeAttempts());
        if (action.hedgeOutcome() != null) {
            recordHedge(action.hedgeOutcome());
        }
        downstream.onError(action.failure());
    }

    private Throwable withSuppressed(Throwable terminalFailure) {
        for (FailureRecord record : failures) {
            Throwable candidateFailure = record.failure();
            if (candidateFailure != terminalFailure) {
                terminalFailure.addSuppressed(candidateFailure);
            }
        }
        return terminalFailure;
    }

    private FailureRecord firstNonRecoverableFailureLocked() {
        return failures.stream()
                .filter(record -> !record.decision().recoverable())
                .min(java.util.Comparator.comparingInt(record -> record.attempt().orderPosition()))
                .orElse(null);
    }

    private int nextUnstartedPositionLocked() {
        for (int position = 0; position < candidateOrder.size(); position++) {
            if (!startedPositions.contains(position)) {
                return position;
            }
        }
        return -1;
    }

    private Attempt reserveAttemptLocked(int orderPosition, boolean speculative) {
        if (terminal || winner != null || !startedPositions.add(orderPosition)) {
            return null;
        }
        Attempt attempt = new Attempt(
                orderPosition,
                candidateOrder.get(orderPosition),
                speculative
        );
        attempts.add(attempt);
        activeAttempts++;
        return attempt;
    }

    private GenerationCancellationHandle clearHedgeTimerLocked() {
        GenerationCancellationHandle timer = hedgeTimer;
        hedgeTimer = null;
        return timer;
    }

    private AiModelCandidate<StreamingChatModel> candidate(int orderPosition) {
        return candidates.get(candidateOrder.get(orderPosition));
    }

    private void recordFailover(FailoverTransition transition) {
        if (metrics != null) {
            metrics.recordFailover(
                    transition.from().provider(),
                    transition.from().modelId(),
                    transition.to().provider(),
                    transition.to().modelId(),
                    transition.errorCategory()
            );
        }
    }

    private void recordHedge(String outcome) {
        if (metrics == null) {
            return;
        }
        AiModelCandidate<?> primary = candidate(0);
        AiModelCandidate<?> shadow = candidate(1);
        metrics.recordHedge(
                primary.provider(),
                primary.modelId(),
                shadow.provider(),
                shadow.modelId(),
                outcome
        );
    }

    @Override
    public void cancel() {
        List<Attempt> active;
        GenerationCancellationHandle timer;
        boolean recordCancellation;
        synchronized (stateLock) {
            if (terminal) {
                return;
            }
            terminal = true;
            timer = clearHedgeTimerLocked();
            active = List.copyOf(attempts);
            recordCancellation = hedgeStarted && !hedgeOutcomeRecorded;
            if (recordCancellation) {
                hedgeOutcomeRecorded = true;
            }
        }
        safeCancel(timer, "取消首 Token 对冲计时器");
        cancelAttempts(active);
        if (recordCancellation) {
            recordHedge("cancelled");
        }
    }

    private void cancelAttempts(List<Attempt> attemptsToCancel) {
        for (Attempt attempt : attemptsToCancel) {
            safeCancel(attempt, "取消首 Token 对冲请求");
        }
    }

    private void safeCancel(GenerationCancellationHandle handle, String operation) {
        if (handle == null) {
            return;
        }
        try {
            handle.cancel();
        } catch (RuntimeException cancellationFailure) {
            log.warn("{}失败: {}", operation,
                    LogExceptionSanitizer.sanitizeMessage(cancellationFailure));
        }
    }

    private final class Attempt implements GenerationCancellationHandle {
        private final int orderPosition;
        private final int candidateIndex;
        private final boolean speculative;

        private boolean finished;
        private boolean cancelled;
        private boolean outputObserved;
        private GenerationCancellationHandle cancellationHandle;

        private Attempt(int orderPosition, int candidateIndex, boolean speculative) {
            this.orderPosition = orderPosition;
            this.candidateIndex = candidateIndex;
            this.speculative = speculative;
        }

        private synchronized boolean canStart() {
            return !finished && !cancelled;
        }

        private synchronized int orderPosition() {
            return orderPosition;
        }

        private synchronized int candidateIndex() {
            return candidateIndex;
        }

        private synchronized boolean speculative() {
            return speculative;
        }

        private synchronized void forward(Runnable callback) {
            if (finished || cancelled) {
                return;
            }
            outputObserved = true;
            if (claimWinner(this)) {
                callback.run();
            }
        }

        private void complete(ChatResponse response) {
            synchronized (this) {
                if (finished || cancelled) {
                    return;
                }
                finished = true;
                cancellationHandle = null;
            }
            HedgedStreamingExecution.this.complete(this, response);
        }

        private void fail(Throwable failure) {
            boolean observed;
            synchronized (this) {
                if (finished || cancelled) {
                    return;
                }
                finished = true;
                observed = outputObserved;
                cancellationHandle = null;
            }
            HedgedStreamingExecution.this.fail(this, observed, failure);
        }

        private void registerCancellationHandle(GenerationCancellationHandle handle) {
            if (handle == null) {
                throw new IllegalArgumentException("供应商取消句柄不能为空");
            }
            GenerationCancellationHandle previous;
            boolean cancelImmediately;
            synchronized (this) {
                if (finished || cancelled) {
                    previous = handle;
                    cancelImmediately = true;
                } else {
                    previous = cancellationHandle;
                    cancellationHandle = handle;
                    cancelImmediately = false;
                }
            }
            if (cancelImmediately || previous != handle) {
                safeCancel(previous, "替换供应商取消句柄");
            }
        }

        @Override
        public void cancel() {
            GenerationCancellationHandle active;
            synchronized (this) {
                if (cancelled || finished) {
                    return;
                }
                cancelled = true;
                active = cancellationHandle;
                cancellationHandle = null;
            }
            safeCancel(active, "取消供应商流");
        }
    }

    private record FailureRecord(
            Attempt attempt,
            Throwable failure,
            AiModelFailoverPolicy.Decision decision
    ) {
    }

    private record FailureTerminal(
            Throwable failure,
            GenerationCancellationHandle timer,
            List<Attempt> activeAttempts,
            String hedgeOutcome
    ) {
    }

    private record FailoverTransition(
            AiModelCandidate<?> from,
            AiModelCandidate<?> to,
            String errorCategory
    ) {
    }
}
