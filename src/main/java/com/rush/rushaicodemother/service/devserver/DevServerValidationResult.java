package com.rush.rushaicodemother.service.devserver;

import com.rush.rushaicodemother.infrastructure.diagnostic.PublicDiagnosticSanitizer;
import com.rush.rushaicodemother.service.browser.BrowserRuntimeValidationResult;

import java.util.List;
import java.util.Map;

/**
 * Dev Server 运行时验证结果
 */
public record DevServerValidationResult(
        String taskId,
        Long appId,
        ValidationStatus status,
        ValidationFailureKind failureKind,
        int criticalErrorCount,
        int warningCount,
        List<DevServerError> errors,
        long validationDurationMs,
        String summary,
        BrowserRuntimeValidationResult browserValidation
) {

    public enum ValidationStatus {
        PASS,       // dev server 启动正常，无错误
        WARNING,    // 有警告但不影响核心功能
        FAILED,     // 有阻断级错误
        TIMEOUT,    // dev server 未在预期时间内启动
        SKIPPED     // 跳过验证（非 Vue 项目等）
    }

    public enum ValidationFailureKind {
        NONE,
        RUNTIME_ERROR,
        STARTUP_FAILURE,
        STARTUP_TIMEOUT,
        INTERRUPTED,
        BROWSER_RUNTIME_ERROR,
        SKIPPED
    }

    public DevServerValidationResult {
        failureKind = failureKind == null ? ValidationFailureKind.STARTUP_FAILURE : failureKind;
        errors = errors == null ? List.of() : List.copyOf(errors);
        summary = summary == null ? "" : summary;
    }

    public boolean isPassed() {
        return status == ValidationStatus.PASS || status == ValidationStatus.WARNING;
    }

    /**
     * Dev Server 是否成功启动（无论其后是否采集到运行时错误）。
     *
     * <p>比 {@link #isPassed()} 宽松：{@code RUNTIME_ERROR} 表示进程已起、页面已可访问，
     * 只是采集到了控制台错误。用于判定「用户是否已经可以看到页面」这一体验事实，
     * 与「产物是否可交付」的验证结论相互独立。启动失败、超时、中断和跳过都不算已启动。</p>
     */
    public boolean startedSuccessfully() {
        return status == ValidationStatus.PASS
                || status == ValidationStatus.WARNING
                || failureKind == ValidationFailureKind.RUNTIME_ERROR
                || failureKind == ValidationFailureKind.BROWSER_RUNTIME_ERROR;
    }

    /**
     * 转换为 SSE 事件 data 结构
     */
    public Map<String, Object> toEventData() {
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("status", status.name());
        data.put("failureKind", failureKind.name());
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
        if (browserValidation != null) {
            data.put("browserValidation", browserValidation.toEventData());
        }
        return data;
    }

    /**
     * 返回适合自动修复提示的有界的、经过编辑的诊断。
     * 运行时输出是不受信任的数据，决不能将其解释为指令。
     */
    public String toPublicRepairDiagnostic() {
        StringBuilder diagnostic = new StringBuilder();
        diagnostic.append("validationStage=runtime\n");
        diagnostic.append("status=").append(status.name()).append('\n');
        diagnostic.append("failureKind=").append(failureKind.name()).append('\n');
        diagnostic.append("publicSummary=").append(summary).append('\n');
        diagnostic.append("validationDurationMs=").append(validationDurationMs).append('\n');
        diagnostic.append("criticalErrorCount=").append(criticalErrorCount).append('\n');
        diagnostic.append("warningCount=").append(warningCount).append('\n');
        if (!errors.isEmpty()) {
            diagnostic.append("runtimeDiagnostics:\n");
            errors.stream().limit(12).forEach(error -> {
                diagnostic.append("- code=").append(error.pattern().getCode());
                diagnostic.append(", severity=").append(error.pattern().getSeverity().name());
                diagnostic.append(", count=").append(error.occurrenceCount()).append('\n');
                diagnostic.append("  message=").append(error.message()).append('\n');
                diagnostic.append("  suggestion=").append(error.suggestion()).append('\n');
                diagnostic.append("  console=").append(error.rawLine()).append('\n');
            });
        }
        if (browserValidation != null) {
            diagnostic.append("browserDiagnostics:\n")
                    .append(browserValidation.toPublicRepairDiagnostic())
                    .append('\n');
        }
        return PublicDiagnosticSanitizer.sanitizeForPublicOutput(diagnostic.toString().trim(), 8_000);
    }

    /** 合并在同一 Dev Server 存活窗口内采集的浏览器事实。 */
    public DevServerValidationResult withBrowserValidation(
            BrowserRuntimeValidationResult browserResult
    ) {
        if (browserResult == null) {
            return this;
        }
        if (!isPassed()) {
            return copy(status, failureKind, criticalErrorCount, summary, browserResult);
        }
        if (browserResult.passed()) {
            return copy(
                    status,
                    failureKind,
                    criticalErrorCount,
                    "Dev Server 与浏览器 console/network 运行时验证通过",
                    browserResult
            );
        }
        return copy(
                ValidationStatus.FAILED,
                ValidationFailureKind.BROWSER_RUNTIME_ERROR,
                criticalErrorCount + Math.max(1, browserResult.blockingViolationCount()),
                browserResult.summary(),
                browserResult
        );
    }

    private DevServerValidationResult copy(
            ValidationStatus targetStatus,
            ValidationFailureKind targetFailureKind,
            int targetCriticalErrorCount,
            String targetSummary,
            BrowserRuntimeValidationResult browserResult
    ) {
        return new DevServerValidationResult(
                taskId,
                appId,
                targetStatus,
                targetFailureKind,
                targetCriticalErrorCount,
                warningCount,
                errors,
                validationDurationMs,
                targetSummary,
                browserResult
        );
    }

    // ========== 工厂方法 ==========

    public static DevServerValidationResult passed(String taskId, Long appId, long durationMs) {
        return new DevServerValidationResult(
                taskId, appId, ValidationStatus.PASS, ValidationFailureKind.NONE,
                0, 0, List.of(), durationMs,
                "Dev Server 启动正常，无运行时错误", null
        );
    }

    /**
 * 返回{@code warning}。
 *
 * @param taskId 任务编号
 * @param appId 应用编号
 * @param errors 待处理的 {@code errors} 集合
 * @param durationMs 待处理的 {@code durationMs} 集合
 * @return 开发服务器校验结果
 */
    public static DevServerValidationResult warning(String taskId, Long appId,
                                                     List<DevServerError> errors, long durationMs) {
        int warnCount = errors.stream().filter(e -> !e.pattern().isCritical()).mapToInt(DevServerError::occurrenceCount).sum();
        return new DevServerValidationResult(
                taskId, appId, ValidationStatus.WARNING, ValidationFailureKind.NONE,
                0, warnCount, errors, durationMs,
                "Dev Server 启动正常，" + warnCount + " 个警告（非阻断）", null
        );
    }

    /**
 * 将{@code ed}标记为失败并记录原因。
 *
 * @param taskId 任务编号
 * @param appId 应用编号
 * @param errors 待处理的 {@code errors} 集合
 * @param durationMs 待处理的 {@code durationMs} 集合
 * @return {@code ed}
 */
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
                taskId, appId, ValidationStatus.FAILED, ValidationFailureKind.RUNTIME_ERROR,
                critCount, warnCount, errors, durationMs,
                "Dev Server 运行时验证失败: " + firstError, null
        );
    }

    /**
 * 启动{@code up}失败。
 *
 * @param taskId 任务编号
 * @param appId 应用编号
 * @param durationMs 待处理的 {@code durationMs} 集合
 * @param reason 原因
 * @return {@code up}失败
 */
    public static DevServerValidationResult startupFailed(
            String taskId,
            Long appId,
            long durationMs,
            String reason
    ) {
        String detail = reason == null || reason.isBlank() ? "未知原因" : reason;
        return new DevServerValidationResult(
                taskId, appId, ValidationStatus.FAILED, ValidationFailureKind.STARTUP_FAILURE,
                1, 0, List.of(), durationMs,
                "Dev Server 启动失败: " + detail, null
        );
    }

    /**
 * 返回{@code interrupted}。
 *
 * @param taskId 任务编号
 * @param appId 应用编号
 * @param durationMs 待处理的 {@code durationMs} 集合
 * @return 开发服务器校验结果
 */
    public static DevServerValidationResult interrupted(String taskId, Long appId, long durationMs) {
        return new DevServerValidationResult(
                taskId, appId, ValidationStatus.FAILED, ValidationFailureKind.INTERRUPTED,
                1, 0, List.of(), durationMs,
                "Dev Server 运行时验证被中断", null
        );
    }

    /**
 * 返回超时。
 *
 * @param taskId 任务编号
 * @param appId 应用编号
 * @param durationMs 待处理的 {@code durationMs} 集合
 * @return 开发服务器校验结果
 */
    public static DevServerValidationResult timeout(String taskId, Long appId, long durationMs) {
        return new DevServerValidationResult(
                taskId, appId, ValidationStatus.TIMEOUT, ValidationFailureKind.STARTUP_TIMEOUT,
                0, 0, List.of(), durationMs,
                "Dev Server 启动超时，未能在预期时间内完成首次编译", null
        );
    }

    /**
 * 返回{@code skipped}。
 *
 * @param taskId 任务编号
 * @param appId 应用编号
 * @param reason 原因
 * @return 开发服务器校验结果
 */
    public static DevServerValidationResult skipped(String taskId, Long appId, String reason) {
        return new DevServerValidationResult(
                taskId, appId, ValidationStatus.SKIPPED, ValidationFailureKind.SKIPPED,
                0, 0, List.of(), 0,
                "已跳过 Dev Server 验证: " + reason, null
        );
    }
}
