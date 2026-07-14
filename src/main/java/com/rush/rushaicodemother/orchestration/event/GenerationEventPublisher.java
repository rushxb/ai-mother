package com.rush.rushaicodemother.orchestration.event;

import com.rush.rushaicodemother.infrastructure.diagnostic.LogExceptionSanitizer;
import com.rush.rushaicodemother.orchestration.GenerationTaskRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class GenerationEventPublisher {

    private static final int MAX_REPLAY_EVENTS_PER_APP = 100;

    private final Map<Long, Deque<GenerationEvent>> replayEvents = new ConcurrentHashMap<>();
    private final Map<Long, Sinks.Many<GenerationEvent>> eventSinks = new ConcurrentHashMap<>();

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
        remember(event);
        eventSinks.computeIfAbsent(appId, this::newSink).tryEmitNext(event);
    }

    /**
     * Publishes a best-effort observability event without allowing event delivery failures to
     * interrupt the generation workflow or prevent task lifecycle cleanup.
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

    public List<GenerationEvent> recent(Long appId) {
        if (appId == null) {
            return List.of();
        }
        Deque<GenerationEvent> events = replayEvents.get(appId);
        if (events == null) {
            return List.of();
        }
        synchronized (events) {
            return List.copyOf(events);
        }
    }

    public void clearRecent(Long appId) {
        if (appId != null) {
            replayEvents.remove(appId);
        }
    }

    public Flux<GenerationEvent> stream(Long appId) {
        if (appId == null) {
            return Flux.empty();
        }
        return Flux.defer(() -> Flux.concat(
                Flux.fromIterable(recent(appId)),
                eventSinks.computeIfAbsent(appId, this::newSink).asFlux()
        ));
    }

    private void remember(GenerationEvent event) {
        Deque<GenerationEvent> events = replayEvents.computeIfAbsent(
                event.appId(),
                key -> new ArrayDeque<>(MAX_REPLAY_EVENTS_PER_APP)
        );
        synchronized (events) {
            events.addLast(event);
            while (events.size() > MAX_REPLAY_EVENTS_PER_APP) {
                events.removeFirst();
            }
        }
    }

    private Sinks.Many<GenerationEvent> newSink(Long appId) {
        return Sinks.many().multicast().directBestEffort();
    }

    private Map<String, Object> immutableEventData(Map<String, Object> data) {
        if (data == null || data.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(data));
    }
}
