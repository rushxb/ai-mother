package com.rush.rushaicodemother.orchestration.eventstream;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.rush.rushaicodemother.config.GenerationEventStreamProperties;
import com.rush.rushaicodemother.core.handler.GenerationPublicEventSanitizer;
import com.rush.rushaicodemother.core.handler.GenerationStreamEvent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/** 用于开发和隔离测试的进程内适配器。 */
@Component
@ConditionalOnProperty(prefix = "app.generation-event-stream", name = "transport",
        havingValue = "local", matchIfMissing = true)
public class LocalGenerationEventStream implements GenerationEventStream {

    private final Cache<String, TaskEventLog> streams;
    private final int replayLimit;

    public LocalGenerationEventStream(GenerationEventStreamProperties properties) {
        this.replayLimit = properties.getMaxEventsPerTask();
        this.streams = Caffeine.newBuilder()
                .maximumSize(properties.getMaxTrackedTasks())
                .expireAfterAccess(properties.getRetention())
                .build();
    }

    /**
 * 发布当前处理结果或领域事件。
 *
 * @param taskId 任务编号
 * @param event 待处理的领域事件
 */
    @Override
    public void publish(String taskId, GenerationStreamEvent event) {
        if (!validTaskId(taskId) || event == null) {
            return;
        }
        GenerationStreamEvent publicEvent = GenerationPublicEventSanitizer.sanitize(event);
        if (publicEvent != null) {
            eventLog(taskId).publish(publicEvent);
        }
    }

    /**
 * 完成{@code Local}生成事件流并持久化终态。
 *
 * @param taskId 任务编号
 */
    @Override
    public void complete(String taskId) {
        if (!validTaskId(taskId)) {
            return;
        }
        eventLog(taskId).complete();
    }

    /**
 * 返回可用。
 *
 * @param taskId 任务编号
 * @return 满足条件时返回 {@code true}，否则返回 {@code false}
 */
    @Override
    public boolean available(String taskId) {
        return validTaskId(taskId) && streams.getIfPresent(taskId) != null;
    }

    /**
 * 返回流。
 *
 * @param taskId 任务编号
 * @param afterSequence 执行后序列
 * @return 异步响应式处理结果
 */
    @Override
    public Flux<SequencedGenerationEvent> stream(String taskId, long afterSequence) {
        if (!validTaskId(taskId)) {
            return Flux.empty();
        }
        if (afterSequence < 0) {
            throw new IllegalArgumentException("generation event cursor cannot be negative");
        }
        return GenerationEventReplayCursor.after(eventLog(taskId).asFlux(), afterSequence)
                .takeUntil(SequencedGenerationEvent::terminal);
    }

    private TaskEventLog eventLog(String taskId) {
        return streams.get(taskId, ignored -> new TaskEventLog(replayLimit));
    }

    private boolean validTaskId(String taskId) {
        return taskId != null && taskId.matches("[A-Za-z0-9_-]{1,128}");
    }

    private static final class TaskEventLog {

        private final Sinks.Many<SequencedGenerationEvent> sink;
        private long sequence;
        private boolean completed;

        private TaskEventLog(int replayLimit) {
            this.sink = Sinks.many().replay().limit(replayLimit);
        }

        private synchronized void publish(GenerationStreamEvent event) {
            if (completed) {
                return;
            }
            sink.emitNext(
                    SequencedGenerationEvent.event(++sequence, event),
                    Sinks.EmitFailureHandler.FAIL_FAST
            );
        }

        private synchronized void complete() {
            if (completed) {
                return;
            }
            completed = true;
            sink.emitNext(
                    SequencedGenerationEvent.complete(++sequence),
                    Sinks.EmitFailureHandler.FAIL_FAST
            );
            sink.emitComplete(Sinks.EmitFailureHandler.FAIL_FAST);
        }

        private Flux<SequencedGenerationEvent> asFlux() {
            return sink.asFlux();
        }
    }
}