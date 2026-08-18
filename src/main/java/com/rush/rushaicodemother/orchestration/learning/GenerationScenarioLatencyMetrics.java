package com.rush.rushaicodemother.orchestration.learning;

/** 首个可用结果与最终交付耗时的观测聚合，尾延迟使用最近秩 P95。 */
public record GenerationScenarioLatencyMetrics(
        long firstUsefulObservedCount,
        Double averageFirstUsefulMs,
        Long p95FirstUsefulMs,
        long deliveredObservedCount,
        Double averageDeliveredMs,
        Long p95DeliveredMs
) {

    public GenerationScenarioLatencyMetrics {
        requireObservation(firstUsefulObservedCount, averageFirstUsefulMs, p95FirstUsefulMs, "首个可用结果");
        requireObservation(deliveredObservedCount, averageDeliveredMs, p95DeliveredMs, "最终交付");
    }

    public double firstUsefulObservationRate(long taskCount) {
        return ratio(firstUsefulObservedCount, taskCount);
    }

    public double deliveryObservationRate(long taskCount) {
        return ratio(deliveredObservedCount, taskCount);
    }

    private static void requireObservation(long count, Double average, Long p95, String label) {
        if (count < 0) {
            throw new IllegalArgumentException(label + "观测数不能为负数");
        }
        if (count == 0 && (average != null || p95 != null)) {
            throw new IllegalArgumentException(label + "无观测时耗时必须为空");
        }
        if (count > 0 && (average == null || !Double.isFinite(average) || average < 0
                || p95 == null || p95 < 0)) {
            throw new IllegalArgumentException(label + "耗时观测不完整");
        }
    }

    private static double ratio(long numerator, long denominator) {
        return denominator == 0 ? 0.0 : (double) numerator / denominator;
    }
}
