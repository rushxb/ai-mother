package com.rush.rushaicodemother.orchestration.artifact;

import java.util.List;

/**
 * 生成前质量门禁结果。
 */
public record QualityGateResult(
        boolean passed,
        String level,
        List<String> blockers,
        List<String> warnings,
        List<String> passes
) {

    /**
 * 返回{@code passed}。
 *
 * @param warnings 待处理的 {@code warnings} 集合
 * @param passes 待处理的 {@code passes} 集合
 * @return 质量门禁结果
 */
    public static QualityGateResult passed(List<String> warnings, List<String> passes) {
        return new QualityGateResult(true, warnings == null || warnings.isEmpty() ? "pass" : "warning",
                List.of(),
                warnings == null ? List.of() : List.copyOf(warnings),
                passes == null ? List.of() : List.copyOf(passes));
    }

    /**
 * 将{@code ed}标记为失败并记录原因。
 *
 * @param blockers 待处理的 {@code blockers} 集合
 * @param warnings 待处理的 {@code warnings} 集合
 * @param passes 待处理的 {@code passes} 集合
 * @return {@code ed}
 */
    public static QualityGateResult failed(List<String> blockers, List<String> warnings, List<String> passes) {
        return new QualityGateResult(false, "blocker",
                blockers == null ? List.of() : List.copyOf(blockers),
                warnings == null ? List.of() : List.copyOf(warnings),
                passes == null ? List.of() : List.copyOf(passes));
    }
}
