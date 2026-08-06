package com.rush.rushaicodemother.model.vo;

import com.rush.rushaicodemother.monitor.latency.GenerationRouteLatencySegmentProfile;
import lombok.Builder;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;
import java.util.List;

/**
 * 路由分段延迟画像的管理端视图。
 */
@Data
@Builder
public class GenerationRouteLatencySegmentVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 运行时路由。 */
    private String route;

    /** 参与统计的成功任务数。 */
    private int taskSampleCount;

    /** 参与统计的 span 数。 */
    private int spanSampleCount;

    /** 未归入任何分段的 span 数。 */
    private int unmappedSpanCount;

    /** 已归入分段的 span 占比。 */
    private double sampleCompletenessPercent;

    /** 任务总时长 p50，单位毫秒。 */
    private long taskTotalP50Ms;

    /** 任务总时长 p90，单位毫秒。 */
    private long taskTotalP90Ms;

    /** 任务总时长 p99，单位毫秒。 */
    private long taskTotalP99Ms;

    /** 样本量与完整率是否达到并行决策门禁。 */
    private boolean sufficientForParallelDecision;

    /** 各分段画像，按 p90 降序。 */
    private List<SegmentLatencyVO> segments;

    /** 计算时刻。 */
    private Instant calculatedAt;

    /**
     * 由领域画像构建视图。
     *
     * @param profile 分段延迟画像
     * @return 管理端视图
     */
    public static GenerationRouteLatencySegmentVO from(GenerationRouteLatencySegmentProfile profile) {
        return GenerationRouteLatencySegmentVO.builder()
                .route(profile.route())
                .taskSampleCount(profile.taskSampleCount())
                .spanSampleCount(profile.spanSampleCount())
                .unmappedSpanCount(profile.unmappedSpanCount())
                .sampleCompletenessPercent(profile.sampleCompletenessPercent())
                .taskTotalP50Ms(profile.taskTotalP50Ms())
                .taskTotalP90Ms(profile.taskTotalP90Ms())
                .taskTotalP99Ms(profile.taskTotalP99Ms())
                .sufficientForParallelDecision(profile.sufficientForParallelDecision())
                .segments(profile.segments().stream()
                        .map(SegmentLatencyVO::from)
                        .toList())
                .calculatedAt(profile.calculatedAt())
                .build();
    }

    /**
     * 单个分段的管理端视图。
     */
    @Data
    @Builder
    public static class SegmentLatencyVO implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        /** 分段名称。 */
        private String segment;

        /** 该分段的 span 数。 */
        private int spanCount;

        /** 分段耗时 p50，单位毫秒。 */
        private long p50DurationMs;

        /** 分段耗时 p90，单位毫秒。 */
        private long p90DurationMs;

        /** 分段耗时 p99，单位毫秒。 */
        private long p99DurationMs;

        /** 分段 p90 占任务总时长 p90 的比例。 */
        private double taskP90SharePercent;

        static SegmentLatencyVO from(GenerationRouteLatencySegmentProfile.SegmentLatency segment) {
            return SegmentLatencyVO.builder()
                    .segment(segment.segment().name().toLowerCase(java.util.Locale.ROOT))
                    .spanCount(segment.spanCount())
                    .p50DurationMs(segment.p50DurationMs())
                    .p90DurationMs(segment.p90DurationMs())
                    .p99DurationMs(segment.p99DurationMs())
                    .taskP90SharePercent(segment.taskP90SharePercent())
                    .build();
        }
    }
}
