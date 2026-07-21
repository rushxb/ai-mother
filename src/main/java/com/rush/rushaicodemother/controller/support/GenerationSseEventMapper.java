package com.rush.rushaicodemother.controller.support;

import cn.hutool.json.JSONUtil;
import com.rush.rushaicodemother.config.GenerationSseProperties;
import com.rush.rushaicodemother.core.handler.GenerationPublicEventSanitizer;
import com.rush.rushaicodemother.core.handler.GenerationStreamEvent;
import com.rush.rushaicodemother.orchestration.eventstream.SequencedGenerationEvent;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;

/** Maps domain generation events to the shared SSE wire format. */
@Component
public class GenerationSseEventMapper {

    public static final String GENERATION_GAP_EVENT = "generation_gap";

    private final Duration heartbeatInterval;

    public GenerationSseEventMapper(GenerationSseProperties properties) {
        if (properties == null || properties.getHeartbeatInterval() == null
                || properties.getHeartbeatInterval().isZero()
                || properties.getHeartbeatInterval().isNegative()) {
            throw new IllegalArgumentException("generation SSE heartbeat interval must be positive");
        }
        this.heartbeatInterval = properties.getHeartbeatInterval();
    }

    public Flux<ServerSentEvent<String>> map(Flux<GenerationStreamEvent> events) {
        if (events == null) {
            throw new IllegalArgumentException("generation event stream cannot be null");
        }
        Flux<ServerSentEvent<String>> domainEvents = events
                .<GenerationStreamEvent>handle((event, sink) -> {
                    GenerationStreamEvent publicEvent = GenerationPublicEventSanitizer.sanitize(event);
                    if (publicEvent != null) {
                        sink.next(publicEvent);
                    }
                })
                .map(event -> ServerSentEvent.<String>builder()
                .event(event.getType())
                .data(JSONUtil.toJsonStr(event))
                .build());
        return withHeartbeats(domainEvents)
                .concatWith(Mono.just(ServerSentEvent.<String>builder()
                        .event("done")
                        .data("")
                        .build()));
    }

    /** Maps the durable task stream without inventing a completion event when the log has no terminal marker. */
    public Flux<ServerSentEvent<String>> mapSequenced(Flux<SequencedGenerationEvent> events) {
        if (events == null) {
            throw new IllegalArgumentException("sequenced generation event stream cannot be null");
        }
        Flux<ServerSentEvent<String>> wireEvents = events
                .<ServerSentEvent<String>>handle((entry, sink) -> {
                    ServerSentEvent<String> mapped = mapSequencedEntry(entry);
                    if (mapped != null) {
                        sink.next(mapped);
                    }
                });
        return withHeartbeats(wireEvents);
    }

    private ServerSentEvent<String> mapSequencedEntry(SequencedGenerationEvent entry) {
        if (entry == null) {
            return null;
        }
        String id = Long.toString(entry.sequence());
        return switch (entry.kind()) {
            case EVENT -> {
                GenerationStreamEvent publicEvent = GenerationPublicEventSanitizer.sanitize(entry.event());
                yield publicEvent == null ? null : ServerSentEvent.<String>builder()
                        .id(id)
                        .event(publicEvent.getType())
                        .data(JSONUtil.toJsonStr(publicEvent))
                        .build();
            }
            case GAP -> ServerSentEvent.<String>builder()
                    .id(id)
                    .event(GENERATION_GAP_EVENT)
                    .data(JSONUtil.toJsonStr(Map.of(
                            "requestedSeq", entry.gap().requestedSeq(),
                            "firstAvailableSeq", entry.gap().firstAvailableSeq(),
                            "recovery", entry.gap().recovery()
                    )))
                    .build();
            case COMPLETE -> ServerSentEvent.<String>builder()
                    .id(id)
                    .event("done")
                    .data("")
                    .build();
        };
    }

    private Flux<ServerSentEvent<String>> withHeartbeats(Flux<ServerSentEvent<String>> wireEvents) {
        return wireEvents.publish(sharedEvents -> Flux.merge(
                sharedEvents,
                Flux.interval(heartbeatInterval)
                        .map(sequence -> ServerSentEvent.<String>builder()
                                .comment("heartbeat")
                                .build())
                        .takeUntilOther(sharedEvents.ignoreElements())
        ));
    }
}
