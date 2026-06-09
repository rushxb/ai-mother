package com.rush.rushaicodemother.model.vo;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class GenerationPerformanceStageStatsVO {

    private String stage;

    private Long count;

    private Long avgDurationMs;

    private Long p50DurationMs;

    private Long p90DurationMs;

    private Long maxDurationMs;
}
