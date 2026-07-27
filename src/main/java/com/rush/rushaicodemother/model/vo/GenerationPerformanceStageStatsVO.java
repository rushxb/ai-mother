package com.rush.rushaicodemother.model.vo;

import lombok.Builder;
import lombok.Data;

/**
 * 生成性能阶段统计接口视图对象。
 */
@Data
@Builder
public class GenerationPerformanceStageStatsVO {

    /** 当前阶段。 */
    private String stage;

    private Long count;

    private Long avgDurationMs;

    private Long p50DurationMs;

    private Long p90DurationMs;

    private Long maxDurationMs;
}
