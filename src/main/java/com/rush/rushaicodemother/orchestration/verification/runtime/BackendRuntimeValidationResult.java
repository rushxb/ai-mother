package com.rush.rushaicodemother.orchestration.verification.runtime;

import java.util.ArrayList;
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

    private static final String DEFAULT_COMMAND_SUMMARY =
            "go run -mod=readonly ./cmd/server";

    public BackendRuntimeValidationResult {
        if (port < 0 || port > 65_535) {
            throw new IllegalArgumentException("后端运行时端口无效");
        }
        durationMs = Math.max(0, durationMs);
        commandSummary = commandSummary == null || commandSummary.isBlank()
                ? DEFAULT_COMMAND_SUMMARY
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
                DEFAULT_COMMAND_SUMMARY,
                List.of(violation));
    }

    /** 从仍由调用者持有的运行时句柄读取当前健康与存活事实。 */
    public static BackendRuntimeValidationResult observe(
            GeneratedBackendRuntimeHandle handle,
            long durationMs
    ) {
        if (handle == null) {
            return failed(durationMs, "backend_runtime_handle_missing");
        }
        GeneratedBackendRuntimeObservation observation = handle.observation();
        List<String> violations = new ArrayList<>(observation == null
                ? List.of("backend_observation_missing")
                : observation.violations());
        boolean processAlive = handle.processAlive();
        if (observation != null && observation.passedValidation() && !processAlive) {
            violations.add("backend_process_exited_after_health");
        }
        return new BackendRuntimeValidationResult(
                handle.port(),
                processAlive,
                durationMs,
                DEFAULT_COMMAND_SUMMARY,
                violations
        );
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
