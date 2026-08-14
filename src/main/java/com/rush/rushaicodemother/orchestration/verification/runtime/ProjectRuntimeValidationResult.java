package com.rush.rushaicodemother.orchestration.verification.runtime;

import com.rush.rushaicodemother.service.devserver.DevServerValidationResult;

import java.util.LinkedHashMap;
import java.util.Map;

/** 跨前端 Dev Server 与后端 HTTP health 的统一运行时验证结果。 */
public record ProjectRuntimeValidationResult(
        boolean passed,
        String status,
        String failureKind,
        int criticalErrorCount,
        int warningCount,
        long durationMs,
        String summary,
        Map<String, Object> eventData,
        Map<String, Object> evidenceDetails,
        String repairDiagnostic
) {

    public ProjectRuntimeValidationResult {
        status = status == null || status.isBlank() ? "FAILED" : status;
        failureKind = failureKind == null || failureKind.isBlank()
                ? "runtime_validation_failed"
                : failureKind;
        summary = summary == null || summary.isBlank()
                ? "项目运行时验证未通过"
                : summary;
        eventData = eventData == null ? Map.of() : Map.copyOf(eventData);
        evidenceDetails = evidenceDetails == null ? Map.of() : Map.copyOf(evidenceDetails);
        repairDiagnostic = repairDiagnostic == null ? "" : repairDiagnostic;
    }

    public static ProjectRuntimeValidationResult fromDevServer(DevServerValidationResult result) {
        if (result == null) {
            return new ProjectRuntimeValidationResult(
                    false,
                    "FAILED",
                    "runtime_result_missing",
                    1,
                    0,
                    0,
                    "前端运行时验证服务未返回结果",
                    Map.of("runtimeKind", "frontend_dev_server"),
                    Map.of("runtimeKind", "frontend_dev_server"),
                    "validationStage=runtime\nruntimeKind=frontend_dev_server\nfailureKind=runtime_result_missing");
        }
        Map<String, Object> eventData = new LinkedHashMap<>(result.toEventData());
        eventData.put("runtimeKind", "frontend_dev_server");
        Map<String, Object> evidenceDetails = Map.of(
                "runtimeKind", "frontend_dev_server",
                "runtimeStatus", result.status().name(),
                "runtimeDurationMs", result.validationDurationMs());
        return new ProjectRuntimeValidationResult(
                result.isPassed(),
                result.status().name(),
                result.failureKind().name(),
                result.criticalErrorCount(),
                result.warningCount(),
                result.validationDurationMs(),
                result.summary(),
                eventData,
                evidenceDetails,
                result.toPublicRepairDiagnostic());
    }

    public static ProjectRuntimeValidationResult fromBackend(BackendRuntimeValidationResult result) {
        BackendRuntimeValidationResult resolved = result == null
                ? BackendRuntimeValidationResult.failed(0, "backend_runtime_result_missing")
                : result;
        Map<String, Object> eventData = new LinkedHashMap<>(resolved.evidenceDetails());
        eventData.put("status", resolved.status());
        eventData.put("failureKind", resolved.failureKind());
        eventData.put("criticalErrorCount", resolved.passed() ? 0 : 1);
        eventData.put("warningCount", 0);
        eventData.put("validationDurationMs", resolved.durationMs());
        eventData.put("summary", resolved.summary());
        return new ProjectRuntimeValidationResult(
                resolved.passed(),
                resolved.status(),
                resolved.failureKind(),
                resolved.passed() ? 0 : 1,
                0,
                resolved.durationMs(),
                resolved.summary(),
                eventData,
                resolved.evidenceDetails(),
                resolved.toPublicRepairDiagnostic());
    }

    public boolean isPassed() {
        return passed;
    }
}
