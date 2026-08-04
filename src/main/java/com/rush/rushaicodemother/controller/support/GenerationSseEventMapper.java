package com.rush.rushaicodemother.controller.support;

import cn.hutool.json.JSONUtil;
import com.rush.rushaicodemother.config.GenerationSseProperties;
import com.rush.rushaicodemother.core.handler.GenerationPublicEventSanitizer;
import com.rush.rushaicodemother.core.handler.GenerationStreamEvent;
import com.rush.rushaicodemother.orchestration.eventstream.SequencedGenerationEvent;
import com.rush.rushaicodemother.orchestration.experience.GenerationExperienceEventMapper;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/** 将域生成事件映射到共享 SSE 有线格式。 */
@Component
public class GenerationSseEventMapper {

    public static final String GENERATION_GAP_EVENT = "generation_gap";

    private final Duration heartbeatInterval;
    private final GenerationExperienceEventMapper experienceEventMapper;

    /**
     * 创建生成 SSE 事件映射器实例并完成必要的依赖和初始状态设置。
     *
     * @param properties 配置属性
     * @param experienceEventMapper 用户体验事件映射器
     */
    public GenerationSseEventMapper(GenerationSseProperties properties,
                                    GenerationExperienceEventMapper experienceEventMapper) {
        if (properties == null || properties.getHeartbeatInterval() == null
                || properties.getHeartbeatInterval().isZero()
                || properties.getHeartbeatInterval().isNegative()) {
            throw new IllegalArgumentException("generation SSE heartbeat interval must be positive");
        }
        if (experienceEventMapper == null) {
            throw new IllegalArgumentException("generation experience event mapper cannot be null");
        }
        this.heartbeatInterval = properties.getHeartbeatInterval();
        this.experienceEventMapper = experienceEventMapper;
    }

    /**
     * 将输入映射为生成 SSE 事件。
     *
     * @param events 事件
     * @return 异步响应式处理结果
     */
    public Flux<ServerSentEvent<String>> map(Flux<GenerationStreamEvent> events) {
        if (events == null) {
            throw new IllegalArgumentException("generation event stream cannot be null");
        }
        Flux<ServerSentEvent<String>> domainEvents = publicEvents(events)
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

    /** 当日志没有终止标记时，映射持久任务流而不创建完成事件。 */
    public Flux<ServerSentEvent<String>> mapSequenced(Flux<SequencedGenerationEvent> events) {
        if (events == null) {
            throw new IllegalArgumentException("sequenced generation event stream cannot be null");
        }
        Flux<ServerSentEvent<String>> wireEvents = Flux.defer(() -> {
            AtomicReference<String> lastUserStage = new AtomicReference<>();
            return events.<ServerSentEvent<String>>handle((entry, sink) -> {
                ServerSentEvent<String> mapped = mapSequencedEntry(entry, lastUserStage);
                if (mapped != null) {
                    sink.next(mapped);
                }
            });
        });
        return withHeartbeats(wireEvents);
    }

    private Flux<GenerationStreamEvent> publicEvents(Flux<GenerationStreamEvent> events) {
        // 去重状态严格归属于单次订阅，避免跨任务串扰和无界全局缓存。
        return Flux.defer(() -> {
            AtomicReference<String> lastUserStage = new AtomicReference<>();
            return events.<GenerationStreamEvent>handle((event, sink) -> {
                GenerationStreamEvent publicEvent = toPublicEvent(event);
                if (publicEvent != null && !isRepeatedUserStage(publicEvent, lastUserStage)) {
                    sink.next(publicEvent);
                }
            });
        });
    }

    /** 将输入映射为{@code Sequenced}条目。 */
    private ServerSentEvent<String> mapSequencedEntry(SequencedGenerationEvent entry,
                                                       AtomicReference<String> lastUserStage) {
        if (entry == null) {
            return null;
        }
        String id = Long.toString(entry.sequence());
        return switch (entry.kind()) {
            case EVENT -> {
                GenerationStreamEvent publicEvent = toPublicEvent(entry.event());
                yield publicEvent == null || isRepeatedUserStage(publicEvent, lastUserStage)
                        ? null
                        : ServerSentEvent.<String>builder()
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

    private GenerationStreamEvent toPublicEvent(GenerationStreamEvent event) {
        return experienceEventMapper.map(event)
                .map(GenerationPublicEventSanitizer::sanitize)
                .orElse(null);
    }

    private boolean isRepeatedUserStage(GenerationStreamEvent event,
                                        AtomicReference<String> lastUserStage) {
        String stage = experienceEventMapper.userProgressStageCode(event);
        if (stage.isBlank()) {
            return false;
        }
        String previous = lastUserStage.getAndSet(stage);
        return stage.equals(previous);
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
