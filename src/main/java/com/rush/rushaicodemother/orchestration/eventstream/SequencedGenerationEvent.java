package com.rush.rushaicodemother.orchestration.eventstream;

import com.rush.rushaicodemother.core.handler.GenerationStreamEvent;

/** 可恢复任务范围生成流使用的有序事件日志条目。 */
public record SequencedGenerationEvent(
        long sequence,
        Kind kind,
        GenerationStreamEvent event,
        GenerationEventGap gap
) {

    public enum Kind {
        EVENT,
        GAP,
        COMPLETE
    }

    /** 创建{@code Sequenced}生成事件实例并完成必要的依赖和初始状态设置。 */
    public SequencedGenerationEvent {
        if (sequence <= 0) {
            throw new IllegalArgumentException("generation event sequence must be positive");
        }
        if (kind == null) {
            throw new IllegalArgumentException("generation event kind cannot be null");
        }
        switch (kind) {
            case EVENT -> {
                if (event == null || gap != null) {
                    throw new IllegalArgumentException("domain event entry must contain only an event");
                }
            }
            case GAP -> {
                if (event != null || gap == null || gap.firstAvailableSeq() - 1 != sequence) {
                    throw new IllegalArgumentException("gap entry must identify the sequence before replay resumes");
                }
            }
            case COMPLETE -> {
                if (event != null || gap != null) {
                    throw new IllegalArgumentException("completion entry cannot contain an event or gap");
                }
            }
        }
    }

    public static SequencedGenerationEvent event(long sequence, GenerationStreamEvent event) {
        return new SequencedGenerationEvent(sequence, Kind.EVENT, event, null);
    }

    /**
 * 返回{@code gap}。
 *
 * @param sequence 序列
 * @param requestedSeq {@code requestedSeq} 对应的调用参数
 * @param firstAvailableSeq {@code firstAvailableSeq} 对应的调用参数
 * @return {@code Sequenced}生成事件
 */
    public static SequencedGenerationEvent gap(long sequence,
                                                long requestedSeq,
                                                long firstAvailableSeq) {
        return new SequencedGenerationEvent(
                sequence,
                Kind.GAP,
                null,
                GenerationEventGap.statusSnapshot(requestedSeq, firstAvailableSeq)
        );
    }

    public static SequencedGenerationEvent complete(long sequence) {
        return new SequencedGenerationEvent(sequence, Kind.COMPLETE, null, null);
    }

    public boolean domainEvent() {
        return kind == Kind.EVENT;
    }

    public boolean terminal() {
        return kind == Kind.COMPLETE;
    }
}