package com.rush.rushaicodemother.orchestration.runtime.task;

import com.rush.rushaicodemother.core.handler.GenerationStreamEvent;
import com.rush.rushaicodemother.model.enums.GenerationTaskStatus;
import com.rush.rushaicodemother.orchestration.eventstream.GenerationTerminalStreamEventFactory;
import com.rush.rushaicodemother.orchestration.eventstream.SequencedGenerationEvent;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.DurableGenerationTaskRecord;
import com.rush.rushaicodemother.orchestration.delivery.GenerationDeliveryReceipt;
import reactor.core.publisher.Flux;

/**
 * 将数据库终态投影为稳定、可关闭的 SSE 尾部。
 *
 * <p>序号使用保留的高位区间，与已过期 Redis replay 的普通递增序号不冲突；同一任务、
 * 同一终态始终产生相同 eventId 和序号，刷新、跨实例恢复不会制造第二个终态事实。</p>
 */
final class DurableGenerationTerminalEventProjection {

    static final long TERMINAL_SEQUENCE = Long.MAX_VALUE - 1;
    static final long COMPLETE_SEQUENCE = Long.MAX_VALUE;

    private DurableGenerationTerminalEventProjection() {
    }

    static Flux<GenerationStreamEvent> legacy(DurableGenerationTaskRecord task) {
        return legacy(task, task == null ? null : task.deliveryReceipt());
    }

    static Flux<GenerationStreamEvent> legacy(DurableGenerationTaskRecord task,
                                              GenerationDeliveryReceipt receipt) {
        return Flux.just(event(task, receipt));
    }

    static Flux<SequencedGenerationEvent> sequenced(DurableGenerationTaskRecord task,
                                                     long afterSequence) {
        return sequenced(task, afterSequence, task == null ? null : task.deliveryReceipt());
    }

    static Flux<SequencedGenerationEvent> sequenced(DurableGenerationTaskRecord task,
                                                     long afterSequence,
                                                     GenerationDeliveryReceipt receipt) {
        if (afterSequence >= COMPLETE_SEQUENCE) {
            return Flux.empty();
        }
        if (afterSequence >= TERMINAL_SEQUENCE) {
            return Flux.just(SequencedGenerationEvent.complete(COMPLETE_SEQUENCE));
        }
        return Flux.just(
                SequencedGenerationEvent.event(TERMINAL_SEQUENCE, event(task, receipt)),
                SequencedGenerationEvent.complete(COMPLETE_SEQUENCE));
    }

    private static GenerationStreamEvent event(DurableGenerationTaskRecord task,
                                               GenerationDeliveryReceipt receipt) {
        if (task == null || !task.terminal()) {
            throw new IllegalArgumentException("durable terminal task is required");
        }
        return GenerationTerminalStreamEventFactory.create(
                task.taskId(), task.status(), receipt);
    }
}
