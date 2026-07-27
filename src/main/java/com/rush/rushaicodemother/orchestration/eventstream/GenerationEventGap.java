package com.rush.rushaicodemother.orchestration.eventstream;

/** 当重播保留不再覆盖请求的游标时发出恢复元数据。 */
public record GenerationEventGap(
        long requestedSeq,
        long firstAvailableSeq,
        String recovery
) {

    public static final String STATUS_SNAPSHOT_RECOVERY = "status_snapshot";

    public GenerationEventGap {
        if (requestedSeq < 0) {
            throw new IllegalArgumentException("requested generation event sequence cannot be negative");
        }
        if (firstAvailableSeq <= 0 || requestedSeq >= firstAvailableSeq - 1) {
            throw new IllegalArgumentException("generation event gap must contain at least one missing sequence");
        }
        if (recovery == null || recovery.isBlank()) {
            throw new IllegalArgumentException("generation event gap recovery cannot be blank");
        }
    }

    public static GenerationEventGap statusSnapshot(long requestedSeq, long firstAvailableSeq) {
        return new GenerationEventGap(requestedSeq, firstAvailableSeq, STATUS_SNAPSHOT_RECOVERY);
    }
}