package com.rush.rushaicodemother.model.vo;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class GenerationPerformanceSummaryVO {

    private Long taskCount;

    private Long successCount;

    private Long failedCount;

    private Long runningCount;

    private Long avgTotalDurationMs;

    private Long p50TotalDurationMs;

    private Long p90TotalDurationMs;

    private List<GenerationPerformanceStageStatsVO> stageStats;

    private List<GenerationPerformanceTaskVO> recentTasks;
}
