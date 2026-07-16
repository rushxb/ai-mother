package com.rush.rushaicodemother.monitor.span;

import java.time.Instant;
import java.util.List;

/** Read port for querying durable generation spans across instances and restarts. */
public interface GenerationSpanQueryService {

    int DEFAULT_LIMIT = 200;
    int MAX_LIMIT = 1_000;

    List<StoredSpan> findByTaskId(String taskId, Integer limit);

    record StoredSpan(
            String spanId,
            String taskId,
            String stage,
            String category,
            String status,
            Instant startedAt,
            Instant endedAt,
            long durationMs,
            String detail
    ) {
    }
}
