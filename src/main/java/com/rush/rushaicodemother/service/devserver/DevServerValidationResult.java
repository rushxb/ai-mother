package com.rush.rushaicodemother.service.devserver;

import java.util.List;
import java.util.Map;

/**
 * Dev Server 运行时验证结果
 */
public record DevServerValidationResult(
        String taskId,
        Long appId,
        ValidationStatus status,
        int criticalErrorCount,
        int warningCount,
        List<DevServerError> errors,
        long validationDurationMs,
        String summary
) {

    public enum ValidationStatus {
        PASS,       // dev server 启动正常，无错误
        WARNING,    // 有警告但不影响核心功能
        FAILED,     // 有阻断级错误
        TIMEOUT,    // dev server 未在预期时间内启动
        SKIPPED     // 跳过验证（非 Vue 项目等）
    }

    public boolean isPassed() {
        return status == ValidationStatus.PASS || status == ValidationStatus.WARNING;
    }

    /**
     * 转换为 SSE 事件 data 结构
     */
    public Map<String, Object> toEventData() {
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("status", status.name());
        data.put("criticalErrorCount", criticalErrorCount);
        data.put("warningCount", warningCount);
        data.put("validationDurationMs", validationDurationMs);
        data.put("summary", summary);
        if (errors != null && !errors.isEmpty()) {
            data.put("errors", errors.stream().map(e -> Map.of(
                    "pattern", e.pattern().getCode(),
                    "severity", e.pattern().getSeverity().name(),
                    "message", e.message(),
                    "suggestion", e.suggestion(),
                    "count", e.occurrenceCount()
            )).toList());
        }
        return data;
    }

    // ========== 工厂方法 ==========

    public static DevServerValidationResult passed(String taskId, Long appId, long durationMs) {
        return new DevServerValidationResult(
                taskId, appId, ValidationStatus.PASS, 0, 0, List.of(), durationMs,
                "Dev Server 启动正常，无运行时错误"
        );
    }

    public static DevServerValidationResult warning(String taskId, Long appId,
                                                     List<DevServerError> errors, long durationMs) {
        int warnCount = errors.stream().filter(e -> !e.pattern().isCritical()).mapToInt(DevServerError::occurrenceCount).sum();
        return new DevServerValidationResult(
                taskId, appId, ValidationStatus.WARNING, 0, warnCount, errors, durationMs,
                "Dev Server 启动正常，" + warnCount + " 个警告（非阻断）"
        );
    }

    public static DevServerValidationResult failed(String taskId, Long appId,
                                                    List<DevServerError> errors, long durationMs) {
        int critCount = (int) errors.stream().filter(e -> e.pattern().isCritical()).count();
        int warnCount = (int) errors.stream().filter(e -> !e.pattern().isCritical()).count();
        String firstError = errors.stream()
                .filter(e -> e.pattern().isCritical())
                .findFirst()
                .map(DevServerError::message)
                .orElse("未知错误");
        return new DevServerValidationResult(
                taskId, appId, ValidationStatus.FAILED, critCount, warnCount, errors, durationMs,
                "Dev Server 运行时验证失败: " + firstError
        );
    }

    public static DevServerValidationResult startupFailed(
            String taskId,
            Long appId,
            long durationMs,
            String reason
    ) {
        String detail = reason == null || reason.isBlank() ? "未知原因" : reason;
        return new DevServerValidationResult(
                taskId, appId, ValidationStatus.FAILED, 1, 0, List.of(), durationMs,
                "Dev Server 启动失败: " + detail
        );
    }

    public static DevServerValidationResult interrupted(String taskId, Long appId, long durationMs) {
        return new DevServerValidationResult(
                taskId, appId, ValidationStatus.FAILED, 1, 0, List.of(), durationMs,
                "Dev Server 运行时验证被中断"
        );
    }

    public static DevServerValidationResult timeout(String taskId, Long appId, long durationMs) {
        return new DevServerValidationResult(
                taskId, appId, ValidationStatus.TIMEOUT, 0, 0, List.of(), durationMs,
                "Dev Server 启动超时，未能在预期时间内完成首次编译"
        );
    }

    public static DevServerValidationResult skipped(String taskId, Long appId, String reason) {
        return new DevServerValidationResult(
                taskId, appId, ValidationStatus.SKIPPED, 0, 0, List.of(), 0,
                "已跳过 Dev Server 验证: " + reason
        );
    }
}
