package com.rush.rushaicodemother.orchestration.eventstream;

import com.rush.rushaicodemother.config.GenerationEventStreamProperties;
import com.rush.rushaicodemother.core.handler.GenerationStreamEvent;
import com.rush.rushaicodemother.infrastructure.diagnostic.LogExceptionSanitizer;
import com.rush.rushaicodemother.monitor.GenerationEventStreamMetricsCollector;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 仅合并共享传输中的相邻 AI 文本增量；本机会话、trace 与工作记忆仍保留逐事件语义。
 */
@Slf4j
final class GenerationEventDeltaCoalescer implements AutoCloseable {

    private static final int MAX_ASYNC_FLUSH_RETRIES = 3;
    private static final int FLUSH_TIMER_THREADS = 2;

    private final boolean enabled;
    private final Duration flushInterval;
    private final int maxChars;
    private final EventWriter writer;
    private final GenerationEventStreamMetricsCollector metricsCollector;
    private final ConcurrentMap<String, DeltaState> states = new ConcurrentHashMap<>();
    private final Semaphore stateSlots;
    private final ScheduledThreadPoolExecutor flushScheduler;
    private final AtomicBoolean closing = new AtomicBoolean(false);

    GenerationEventDeltaCoalescer(GenerationEventStreamProperties properties,
                                  EventWriter writer,
                                  GenerationEventStreamMetricsCollector metricsCollector) {
        GenerationEventStreamProperties requiredProperties = Objects.requireNonNull(
                properties, "生成事件流配置不能为空");
        this.writer = Objects.requireNonNull(writer, "生成事件写入器不能为空");
        this.metricsCollector = Objects.requireNonNull(metricsCollector, "生成事件流指标收集器不能为空");
        this.enabled = requiredProperties.isDeltaCoalescingEnabled();
        this.flushInterval = requiredProperties.getDeltaFlushInterval();
        this.maxChars = requiredProperties.getDeltaMaxChars();
        this.stateSlots = new Semaphore(requiredProperties.getMaxTrackedTasks());
        this.flushScheduler = createScheduler();
    }

    /** 发布当前处理结果或领域事件。 */
    void publish(String taskId, GenerationStreamEvent event) {
        if (!enabled || !isCoalescibleDelta(event)) {
            if (enabled) {
                flushAndRemove(taskId, "barrier");
            }
            writer.publish(taskId, event);
            return;
        }
        publishDelta(taskId, event);
    }

    void complete(String taskId) {
        complete(taskId, null);
    }

    void complete(String taskId, GenerationStreamEvent terminalEvent) {
        if (enabled) {
            flushAndRemove(taskId, "complete");
        }
        writer.complete(taskId, terminalEvent);
    }

    /** 发布增量。 */
    private void publishDelta(String taskId, GenerationStreamEvent event) {
        // 按既定顺序逐项处理，并在达到资源或状态边界时提前结束。
        while (true) {
            DeltaState state = stateFor(taskId);
            if (state == null) {
                metricsCollector.recordDeltaInput(closing.get() ? "shutdown_bypass" : "capacity_bypass");
                flushAndRemove(taskId, "bypass");
                writer.publish(taskId, event);
                return;
            }
            synchronized (state) {
                if (state.closed) {
                    continue;
                }
                if (!state.firstDeltaPublished) {
                    metricsCollector.recordDeltaInput("immediate");
                    try {
                        writer.publish(taskId, event);
                        state.firstDeltaPublished = true;
                        scheduleLocked(taskId, state, flushInterval);
                    } catch (RuntimeException failure) {
                        closeStateLocked(taskId, state);
                        throw failure;
                    }
                    return;
                }

                String text = event.getText() == null ? "" : event.getText();
                if (text.length() >= maxChars) {
                    flushLocked(taskId, state, "size");
                    metricsCollector.recordDeltaInput("oversized");
                    writer.publish(taskId, event);
                    scheduleLocked(taskId, state, flushInterval);
                    return;
                }
                if (state.pendingText.length() + text.length() > maxChars) {
                    flushLocked(taskId, state, "size");
                }
                state.pendingText.append(text);
                state.pendingEventCount++;
                metricsCollector.recordDeltaInput("buffered");
                if (state.pendingText.length() >= maxChars) {
                    flushLocked(taskId, state, "size");
                }
                scheduleLocked(taskId, state, flushInterval);
                return;
            }
        }
    }

    /** 返回状态{@code For}。 */
    private DeltaState stateFor(String taskId) {
        if (closing.get()) {
            return null;
        }
        return states.computeIfAbsent(taskId, ignored -> {
            if (!stateSlots.tryAcquire()) {
                return null;
            }
            if (closing.get()) {
                stateSlots.release();
                return null;
            }
            return new DeltaState();
        });
    }

    /** 处理{@code flush}{@code And}{@code Remove}。 */
    private void flushAndRemove(String taskId, String trigger) {
        DeltaState state = states.get(taskId);
        if (state == null) {
            return;
        }
        synchronized (state) {
            if (state.closed) {
                return;
            }
            flushLocked(taskId, state, trigger);
            closeStateLocked(taskId, state);
        }
    }

    /** 处理{@code flush}{@code Locked}。 */
    private void flushLocked(String taskId, DeltaState state, String trigger) {
        if (state.pendingEventCount == 0) {
            state.asyncFlushRetries = 0;
            state.asyncRetryExhausted = false;
            return;
        }
        int eventCount = state.pendingEventCount;
        String text = state.pendingText.toString();
        try {
            writer.publish(taskId, GenerationStreamEvent.aiDelta(text));
            metricsCollector.recordDeltaFlush(trigger, "success", eventCount, text.length());
            state.pendingText.setLength(0);
            state.pendingEventCount = 0;
            state.asyncFlushRetries = 0;
            state.asyncRetryExhausted = false;
        } catch (RuntimeException failure) {
            metricsCollector.recordDeltaFlush(trigger, "failed", eventCount, text.length());
            throw failure;
        }
    }

    /** 处理调度{@code Locked}。 */
    private void scheduleLocked(String taskId, DeltaState state, Duration delay) {
        if (state.closed || closing.get() || state.asyncRetryExhausted) {
            return;
        }
        ScheduledFuture<?> current = state.flushFuture;
        if (current != null && !current.isDone()) {
            return;
        }
        try {
            state.flushFuture = flushScheduler.schedule(
                    () -> flushOnTimer(taskId, state),
                    delay.toNanos(),
                    TimeUnit.NANOSECONDS
            );
        } catch (RejectedExecutionException shutdownRace) {
            state.flushFuture = null;
            state.asyncRetryExhausted = true;
            log.debug("生成事件 Delta 冲刷调度器已关闭，taskId={}", taskId);
        }
    }

    /** 处理{@code flush}{@code On}{@code Timer}。 */
    private void flushOnTimer(String taskId, DeltaState state) {
        synchronized (state) {
            if (state.closed) {
                return;
            }
            state.flushFuture = null;
            if (state.pendingEventCount == 0) {
                closeStateLocked(taskId, state);
                return;
            }
            String trigger = state.asyncFlushRetries == 0 ? "window" : "retry";
            try {
                flushLocked(taskId, state, trigger);
                scheduleLocked(taskId, state, flushInterval);
            } catch (RuntimeException failure) {
                if (state.asyncFlushRetries < MAX_ASYNC_FLUSH_RETRIES) {
                    state.asyncFlushRetries++;
                    long multiplier = 1L << (state.asyncFlushRetries - 1);
                    scheduleLocked(taskId, state, flushInterval.multipliedBy(multiplier));
                    return;
                }
                state.asyncRetryExhausted = true;
                log.warn("生成事件 Delta 异步冲刷重试耗尽，等待下一顺序边界重试，taskId={}, error={}",
                        taskId, LogExceptionSanitizer.sanitizeMessage(failure));
            }
        }
    }

    /** 关闭状态{@code Locked}并释放资源。 */
    private void closeStateLocked(String taskId, DeltaState state) {
        if (state.closed) {
            return;
        }
        state.closed = true;
        ScheduledFuture<?> future = state.flushFuture;
        state.flushFuture = null;
        if (future != null) {
            future.cancel(false);
        }
        if (states.remove(taskId, state)) {
            stateSlots.release();
        }
    }

    private boolean isCoalescibleDelta(GenerationStreamEvent event) {
        return event != null
                && GenerationStreamEvent.AI_DELTA.equals(event.getType())
                && (event.getData() == null || event.getData().isEmpty());
    }

    private ScheduledThreadPoolExecutor createScheduler() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(
                FLUSH_TIMER_THREADS,
                Thread.ofPlatform().daemon(true).name("generation-event-delta-flush-", 0).factory()
        );
        executor.setRemoveOnCancelPolicy(true);
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        executor.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        return executor;
    }

    /** 关闭生成事件增量合并器并释放资源。 */
    @Override
    public void close() {
        if (!closing.compareAndSet(false, true)) {
            return;
        }
        states.forEach((taskId, state) -> {
            try {
                flushAndRemove(taskId, "shutdown");
            } catch (RuntimeException failure) {
                log.warn("应用关闭前无法冲刷生成事件 Delta，taskId={}, error={}",
                        taskId, LogExceptionSanitizer.sanitizeMessage(failure));
            }
        });
        flushScheduler.shutdownNow();
    }

    interface EventWriter {

        void publish(String taskId, GenerationStreamEvent event);

        void complete(String taskId);

        default void complete(String taskId, GenerationStreamEvent terminalEvent) {
            if (terminalEvent != null) {
                publish(taskId, terminalEvent);
            }
            complete(taskId);
        }
    }

    private static final class DeltaState {

        private final StringBuilder pendingText = new StringBuilder();
        private boolean firstDeltaPublished;
        private int pendingEventCount;
        private int asyncFlushRetries;
        private boolean asyncRetryExhausted;
        private boolean closed;
        private ScheduledFuture<?> flushFuture;
    }
}
