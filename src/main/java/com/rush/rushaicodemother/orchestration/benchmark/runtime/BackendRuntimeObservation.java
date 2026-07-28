package com.rush.rushaicodemother.orchestration.benchmark.runtime;

import java.util.List;

/** 后端运行时探测产生的确定性证据。 */
public record BackendRuntimeObservation(List<String> violations) {

    /** 创建后端运行时观测实例并完成必要的依赖和初始状态设置。 */
    public BackendRuntimeObservation {
        violations = violations == null
                ? List.of("backend_observation_missing")
                : violations.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
    }

    public static BackendRuntimeObservation passed() {
        return new BackendRuntimeObservation(List.of());
    }

    public static BackendRuntimeObservation failed(String violation) {
        return new BackendRuntimeObservation(List.of(violation));
    }

    public boolean passedValidation() {
        return violations.isEmpty();
    }
}
