package com.rush.rushaicodemother.monitor.latency;

import java.time.Instant;
import java.util.List;

/**
 * 单个路由的分段延迟画像。
 *
 * <p>用于回答「该路由的准备阶段占可预览时间多少、值不值得做并行」。样本不足时不做静默近似：
 * {@link #sufficientForParallelDecision()} 会显式为 {@code false}，调用方必须据此拒绝下结论。</p>
 *
 * @param route 运行时路由
 * @param taskSampleCount 参与统计的成功任务数
 * @param spanSampleCount 参与统计的 span 数
 * @param unmappedSpanCount 未归入任何分段的 span 数（父跨度与历史类别）
 * @param sampleCompletenessPercent 已归入分段的 span 占比
 * @param taskTotalP50Ms 任务总时长 p50
 * @param taskTotalP90Ms 任务总时长 p90
 * @param taskTotalP99Ms 任务总时长 p99
 * @param sufficientForParallelDecision 样本量与完整率是否达到并行决策门禁
 * @param segments 各分段画像，按 p90 降序
 * @param calculatedAt 计算时刻
 */
public record GenerationRouteLatencySegmentProfile(
        String route,
        int taskSampleCount,
        int spanSampleCount,
        int unmappedSpanCount,
        double sampleCompletenessPercent,
        long taskTotalP50Ms,
        long taskTotalP90Ms,
        long taskTotalP99Ms,
        boolean sufficientForParallelDecision,
        List<SegmentLatency> segments,
        Instant calculatedAt
) {

    public GenerationRouteLatencySegmentProfile {
        segments = segments == null ? List.of() : List.copyOf(segments);
    }

    /**
     * 单个分段的延迟画像。
     *
     * @param segment 分段
     * @param spanCount 该分段的 span 数
     * @param p50DurationMs 分段耗时 p50
     * @param p90DurationMs 分段耗时 p90
     * @param p99DurationMs 分段耗时 p99
     * @param taskP90SharePercent 分段 p90 占任务总时长 p90 的比例
     */
    public record SegmentLatency(
            GenerationLatencySegment segment,
            int spanCount,
            long p50DurationMs,
            long p90DurationMs,
            long p99DurationMs,
            double taskP90SharePercent
    ) {
    }
}
