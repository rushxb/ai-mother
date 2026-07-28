package com.rush.rushaicodemother.model.vo;

import com.rush.rushaicodemother.monitor.latency.GenerationTaskLatencyLedger;

import java.time.Instant;
import java.util.List;

/** 任务的非重复计数挂钟归因的管理员视图。 */
public record GenerationTaskLatencyLedgerVO(
        String taskId,
        Long appId,
        Long userId,
        String route,
        String status,
        String stage,
        Instant submittedAt,
        Instant deadlineAt,
        Instant completedAt,
        Instant calculatedAt,
        long totalLatencyMs,
        long attributedLatencyMs,
        long unattributedLatencyMs,
        double attributionCoveragePercent,
        long overlappingLatencyMs,
        long deadlineOvershootMs,
        int spanCount,
        int usableSpanCount,
        boolean spanLimitReached,
        String dominantCategory,
        List<CategoryLatencyVO> categories
) {

    /**
 * 根据输入数据创建当前对象。
 *
 * @param ledger {@code ledger} 对应的调用参数
 * @return 生成任务{@code Latency}{@code Ledger}视图对象
 */
    public static GenerationTaskLatencyLedgerVO from(GenerationTaskLatencyLedger ledger) {
        return new GenerationTaskLatencyLedgerVO(
                ledger.taskId(), ledger.appId(), ledger.userId(), ledger.route(),
                ledger.status(), ledger.stage(), ledger.submittedAt(), ledger.deadlineAt(),
                ledger.completedAt(), ledger.calculatedAt(), ledger.totalLatencyMs(),
                ledger.attributedLatencyMs(), ledger.unattributedLatencyMs(),
                ledger.attributionCoveragePercent(), ledger.overlappingLatencyMs(),
                ledger.deadlineOvershootMs(), ledger.spanCount(), ledger.usableSpanCount(),
                ledger.spanLimitReached(), ledger.dominantCategory(),
                ledger.categories().stream().map(CategoryLatencyVO::from).toList()
        );
    }

    public record CategoryLatencyVO(
            String category,
            int spanCount,
            long attributedDurationMs,
            long inclusiveDurationMs,
            double taskPercent
    ) {
        private static CategoryLatencyVO from(GenerationTaskLatencyLedger.CategoryLatency category) {
            return new CategoryLatencyVO(
                    category.category(), category.spanCount(), category.attributedDurationMs(),
                    category.inclusiveDurationMs(), category.taskPercent());
        }
    }
}
