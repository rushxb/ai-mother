package com.rush.rushaicodemother.orchestration.verification.runtime;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 生产与评测链路可共同消费的后端运行时事实。 */
public record BackendRuntimeValidationResult(
        int port,
        boolean processAlive,
        long durationMs,
        String commandSummary,
        List<String> violations
) {

    public BackendRuntimeValidationResult {
        if (port < 0 || port > 65_535) {
            throw new IllegalArgumentException("后端运行时端口无效");
        }
        durationMs = Math.max(0, durationMs);
        commandSummary = commandSummary == null || commandSummary.isBlank()
                ? "go run -mod=readonly ./cmd/server"
                : commandSummary.trim();
        violations = violations == null
                ? List.of("backend_observation_missing")
                : violations.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
    }

    public static BackendRuntimeValidationResult failed(
            long durationMs,
            String violation
    ) {
        return new BackendRuntimeValidationResult(
                0,
                false,
                durationMs,
                "go run -mod=readonly ./cmd/server",
                List.of(violation));
    }

    public boolean passed() {
        return port > 0 && processAlive && violations.isEmpty();
    }

    public String status() {
        return passed() ? "PASS" : "FAILED";
    }

    public String failureKind() {
        if (passed()) {
            return "NONE";
        }
        return violations.isEmpty()
                ? "backend_process_exited_after_health"
                : violations.getFirst();
    }

    public String summary() {
        return passed()
                ? "后端进程、端口与 HTTP health 验证通过"
                : "后端运行时验证未通过: " + failureKind();
    }

    public Map<String, Object> evidenceDetails() {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("runtimeKind", "backend_http_health");
        details.put("commandSummary", commandSummary);
        details.put("durationMs", durationMs);
        details.put("port", port);
        details.put("processAlive", processAlive);
        details.put("violations", violations);
        return Map.copyOf(details);
    }

    public String toPublicRepairDiagnostic() {
        return """
                validationStage=runtime
                runtimeKind=backend_http_health
                status=%s
                failureKind=%s
                command=%s
                durationMs=%d
                processAlive=%s
                violations=%s
                """.formatted(
                status(),
                failureKind(),
                commandSummary,
                durationMs,
                processAlive,
                violations).trim();
    }
}
