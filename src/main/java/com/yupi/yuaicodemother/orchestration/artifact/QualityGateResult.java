package com.yupi.yuaicodemother.orchestration.artifact;

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

    public static QualityGateResult passed(List<String> warnings, List<String> passes) {
        return new QualityGateResult(true, warnings == null || warnings.isEmpty() ? "pass" : "warning",
                List.of(),
                warnings == null ? List.of() : List.copyOf(warnings),
                passes == null ? List.of() : List.copyOf(passes));
    }

    public static QualityGateResult failed(List<String> blockers, List<String> warnings, List<String> passes) {
        return new QualityGateResult(false, "blocker",
                blockers == null ? List.of() : List.copyOf(blockers),
                warnings == null ? List.of() : List.copyOf(warnings),
                passes == null ? List.of() : List.copyOf(passes));
    }
}
