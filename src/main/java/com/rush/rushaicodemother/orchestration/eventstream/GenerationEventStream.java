package com.rush.rushaicodemother.orchestration.eventstream;

import com.rush.rushaicodemother.core.handler.GenerationStreamEvent;
import reactor.core.publisher.Flux;

/** 用于任务范围生成事件的跨实例传输端口。 */
public interface GenerationEventStream {

    void publish(String taskId, GenerationStreamEvent event);

    void complete(String taskId);

    boolean available(String taskId);

    /** 为应用程序范围的兼容性端点保留旧投影。 */
    default Flux<GenerationStreamEvent> stream(String taskId) {
        return stream(taskId, 0L)
                .filter(SequencedGenerationEvent::domainEvent)
                .map(SequencedGenerationEvent::event);
    }

    /** 严格按照提供的序列重播条目，然后跟踪实时事件。 */
    Flux<SequencedGenerationEvent> stream(String taskId, long afterSequence);
}