package com.rush.rushaicodemother.orchestration.verification.runtime;

import java.util.List;

/** 后端进程、端口和 HTTP health 探测产生的确定性事实。 */
public record GeneratedBackendRuntimeObservation(List<String> violations) {

    public GeneratedBackendRuntimeObservation {
        violations = violations == null
                ? List.of("backend_observation_missing")
                : violations.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
    }

    public static GeneratedBackendRuntimeObservation passed() {
        return new GeneratedBackendRuntimeObservation(List.of());
    }

    public static GeneratedBackendRuntimeObservation failed(String violation) {
        return new GeneratedBackendRuntimeObservation(List.of(violation));
    }

    public boolean passedValidation() {
        return violations.isEmpty();
    }
}
