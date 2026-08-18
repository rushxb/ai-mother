package com.rush.rushaicodemother.orchestration.learning;

/** 质量观测聚合；缺失观测与失败必须分开，避免把 NULL 当成通过。 */
public record GenerationScenarioQualityMetrics(
        long taskCount,
        long successCount,
        long validationRequiredCount,
        long validationObservedCount,
        long firstBuildPassCount,
        long repairObservedCount,
        long totalRepairRounds,
        long feedbackCount,
        long lowRatingCount,
        Double averageRating
) {

    public GenerationScenarioQualityMetrics {
        requireCount(taskCount, "任务数");
        requireBoundedCount(successCount, taskCount, "成功任务数");
        requireBoundedCount(validationRequiredCount, taskCount, "需要验证任务数");
        requireBoundedCount(validationObservedCount, validationRequiredCount, "验证观测数");
        requireBoundedCount(firstBuildPassCount, validationObservedCount, "首次构建通过数");
        requireBoundedCount(repairObservedCount, taskCount, "修复轮次观测数");
        requireCount(totalRepairRounds, "修复轮次");
        if (repairObservedCount == 0 && totalRepairRounds > 0) {
            throw new IllegalArgumentException("无修复观测时修复轮次必须为零");
        }
        requireBoundedCount(feedbackCount, taskCount, "反馈数");
        requireBoundedCount(lowRatingCount, feedbackCount, "低评分数");
        if (feedbackCount == 0 && averageRating != null) {
            throw new IllegalArgumentException("无反馈时平均评分必须为空");
        }
        if (feedbackCount > 0 && (averageRating == null
                || !Double.isFinite(averageRating)
                || averageRating < 1.0 || averageRating > 5.0)) {
            throw new IllegalArgumentException("平均评分必须位于 1 到 5 之间");
        }
    }

    public double successRate() {
        return ratio(successCount, taskCount);
    }

    /** 没有要求构建验证的场景不制造虚假缺失；有要求时必须按实际观测计算。 */
    public double validationObservationRate() {
        return validationRequiredCount == 0 ? 1.0 : ratio(validationObservedCount, validationRequiredCount);
    }

    public double firstBuildPassRate() {
        return validationRequiredCount == 0 ? 1.0 : ratio(firstBuildPassCount, validationRequiredCount);
    }

    public double lowRatingRate() {
        return ratio(lowRatingCount, feedbackCount);
    }

    public double averageRepairRounds() {
        return ratio(totalRepairRounds, taskCount);
    }

    private static double ratio(long numerator, long denominator) {
        return denominator == 0 ? 0.0 : (double) numerator / denominator;
    }

    private static void requireCount(long value, String label) {
        if (value < 0) {
            throw new IllegalArgumentException(label + "不能为负数");
        }
    }

    private static void requireBoundedCount(long value, long maximum, String label) {
        if (value < 0 || value > maximum) {
            throw new IllegalArgumentException(label + "超出有效范围");
        }
    }
}
