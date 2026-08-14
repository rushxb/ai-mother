package com.rush.rushaicodemother.orchestration.event;

import com.rush.rushaicodemother.infrastructure.diagnostic.LogExceptionSanitizer;
import com.rush.rushaicodemother.orchestration.GenerationTaskRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class GenerationEventPublisher {

    private static final int MAX_REPLAY_EVENTS_PER_APP = 100;
    private static final int MAX_TRACKED_APPS = 512;
    private static final Duration REPLAY_RETENTION = Duration.ofMinutes(30);

    private final Object stateMonitor = new Object();
    private final Map<Long, Sinks.Many<GenerationEvent>> eventSinks = new HashMap<>();
    private final Deque<Sinks.Many<GenerationEvent>> sinksPendingCompletion = new ArrayDeque<>();
    private final GenerationEventReplayBuffer replayBuffer;
    private final int maxReplayEventsPerApp;

    public GenerationEventPublisher() {
        this(Clock.systemUTC(), REPLAY_RETENTION, MAX_TRACKED_APPS, MAX_REPLAY_EVENTS_PER_APP);
    }

    GenerationEventPublisher(Clock clock,
                             Duration replayRetention,
                             int maxTrackedApps,
                             int maxReplayEventsPerApp) {
        this.maxReplayEventsPerApp = maxReplayEventsPerApp;
        this.replayBuffer = new GenerationEventReplayBuffer(
                clock,
                replayRetention,
                maxTrackedApps,
                maxReplayEventsPerApp,
                this::detachSink);
    }

    /** 按稳定 eventId 幂等发布 outbox 事件。 */
    public void publishIdempotently(GenerationEvent event) {
        if (event == null || event.appId() == null || event.data() == null) {
            return;
        }
        Object rawEventId = event.data().get("eventId");
        if (rawEventId == null || String.valueOf(rawEventId).isBlank()) {
            throw new IllegalArgumentException("幂等生成事件必须包含 eventId");
        }
        String eventId = String.valueOf(rawEventId);
        List<Sinks.Many<GenerationEvent>> sinksToComplete;
        synchronized (stateMonitor) {
            if (replayBuffer.append(event, eventId)) {
                emit(event);
            }
            sinksToComplete = drainPendingSinkCompletions();
        }
        completeSinks(sinksToComplete);
    }

    /** 立即发布与持久 outbox 共用稳定事件 ID。 */
    public void publishIdempotently(GenerationTaskRequest request,
                                    GenerationEventType type,
                                    String message,
                                    Map<String, Object> data) {
        Long appId = request == null || request.app() == null ? null : request.app().getId();
        Long userId = request == null || request.loginUser() == null ? null : request.loginUser().getId();
        publishIdempotently(new GenerationEvent(
                appId, userId, type, message, immutableEventData(data), Instant.now()));
    }

    /**
     * 发布当前处理结果或领域事件。
     *
     * @param request 请求参数
     * @param type 目标类型
     * @param message 消息内容
     * @param data {@code data} 对应的调用参数
     */
    public void publish(GenerationTaskRequest request,
                        GenerationEventType type,
                        String message,
                        Map<String, Object> data) {
        Long appId = request == null || request.app() == null ? null : request.app().getId();
        Long userId = request == null || request.loginUser() == null ? null : request.loginUser().getId();
        GenerationEvent event = new GenerationEvent(
                appId,
                userId,
                type,
                message,
                immutableEventData(data),
                Instant.now()
        );
        log.info("生成任务事件: appId={}, userId={}, type={}, message={}, data={}",
                appId,
                userId,
                type == null ? null : type.getValue(),
                LogExceptionSanitizer.sanitizeValue(message, 1_000),
                LogExceptionSanitizer.sanitizeValue(event.data(), 4_000));
        if (appId == null) {
            return;
        }
        List<Sinks.Many<GenerationEvent>> sinksToComplete;
        synchronized (stateMonitor) {
            replayBuffer.append(event, null);
            emit(event);
            sinksToComplete = drainPendingSinkCompletions();
        }
        completeSinks(sinksToComplete);
    }

    /**
     * 发布尽力而为的可观察性事件，不允许事件传递失败
     * 中断生成工作流程或阻止任务生命周期清理。
     */
    public void publishSafely(GenerationTaskRequest request,
                              GenerationEventType type,
                              String message,
                              Map<String, Object> data) {
        try {
            publish(request, type, message, data);
        } catch (RuntimeException exception) {
            Long appId = request == null || request.app() == null ? null : request.app().getId();
            log.warn("Failed to publish generation event, appId: {}, type: {}",
                    appId, type == null ? null : type.getValue(),
                    LogExceptionSanitizer.sanitize(exception));
        }
    }

    /**
     * 返回应用的最近生成事件。
     *
     * @param appId 应用编号
     * @return 生成事件集合
     */
    public List<GenerationEvent> recent(Long appId) {
        List<GenerationEvent> replay;
        List<Sinks.Many<GenerationEvent>> sinksToComplete;
        synchronized (stateMonitor) {
            replay = replayBuffer.snapshot(appId);
            sinksToComplete = drainPendingSinkCompletions();
        }
        completeSinks(sinksToComplete);
        return replay;
    }

    /**
     * 清理应用的重放窗口并关闭实时流。
     *
     * @param appId 应用编号
     */
    public void clearRecent(Long appId) {
        List<Sinks.Many<GenerationEvent>> sinksToComplete;
        synchronized (stateMonitor) {
            replayBuffer.remove(appId);
            sinksToComplete = drainPendingSinkCompletions();
        }
        completeSinks(sinksToComplete);
    }

    /**
     * 返回无历史与实时事件窗口的订阅流。
     *
     * @param appId 应用编号
     * @return 异步响应式处理结果
     */
    public Flux<GenerationEvent> stream(Long appId) {
        if (appId == null) {
            return Flux.empty();
        }
        return Flux.defer(() -> {
            Flux<GenerationEvent> eventStream;
            List<Sinks.Many<GenerationEvent>> sinksToComplete;
            synchronized (stateMonitor) {
                List<GenerationEvent> replay = replayBuffer.open(appId);
                if (!replay.isEmpty() && isTerminal(replay.getLast().type())) {
                    eventStream = Flux.fromIterable(replay);
                } else {
                    Sinks.Many<GenerationEvent> sink = eventSinks.get(appId);
                    if (sink == null) {
                        sink = newSink(appId);
                        replay.forEach(sink::tryEmitNext);
                        eventSinks.put(appId, sink);
                    }
                    eventStream = sink.asFlux();
                }
                sinksToComplete = drainPendingSinkCompletions();
            }
            completeSinks(sinksToComplete);
            return eventStream;
        });
    }

    int trackedSinkCount() {
        synchronized (stateMonitor) {
            return eventSinks.size();
        }
    }

    private Sinks.Many<GenerationEvent> newSink(Long ignoredAppId) {
        return Sinks.many().replay().limit(maxReplayEventsPerApp);
    }

    private void emit(GenerationEvent event) {
        Sinks.Many<GenerationEvent> sink = eventSinks.computeIfAbsent(event.appId(), this::newSink);
        boolean terminal = isTerminal(event.type());
        if (terminal) {
            eventSinks.remove(event.appId(), sink);
        }
        sink.tryEmitNext(event);
        if (terminal) {
            sinksPendingCompletion.addLast(sink);
        }
    }

    private void detachSink(Long appId) {
        Sinks.Many<GenerationEvent> sink = eventSinks.remove(appId);
        if (sink != null) {
            sinksPendingCompletion.addLast(sink);
        }
    }

    private List<Sinks.Many<GenerationEvent>> drainPendingSinkCompletions() {
        if (sinksPendingCompletion.isEmpty()) {
            return List.of();
        }
        List<Sinks.Many<GenerationEvent>> sinks = List.copyOf(sinksPendingCompletion);
        sinksPendingCompletion.clear();
        return sinks;
    }

    private void completeSinks(List<Sinks.Many<GenerationEvent>> sinks) {
        sinks.forEach(Sinks.Many::tryEmitComplete);
    }

    private boolean isTerminal(GenerationEventType type) {
        return type == GenerationEventType.TASK_DONE
                || type == GenerationEventType.TASK_FAILED
                || type == GenerationEventType.TASK_CANCELLED;
    }

    private Map<String, Object> immutableEventData(Map<String, Object> data) {
        if (data == null || data.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(data));
    }
}
