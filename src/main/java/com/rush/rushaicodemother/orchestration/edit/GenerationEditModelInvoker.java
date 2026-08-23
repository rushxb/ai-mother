package com.rush.rushaicodemother.orchestration.edit;

import com.rush.rushaicodemother.ai.AiCodeEditService;
import com.rush.rushaicodemother.ai.AiCodeEditServiceFactory;
import com.rush.rushaicodemother.ai.model.EditResult;
import com.rush.rushaicodemother.core.error.GenerationErrorClassifier;
import com.rush.rushaicodemother.monitor.MonitorContext;
import com.rush.rushaicodemother.monitor.MonitorContextHolder;
import com.rush.rushaicodemother.monitor.GenerationPerformanceMonitorService;
import com.rush.rushaicodemother.monitor.span.GenerationSpanCategory;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationBudgetExceededException;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationBudgetKind;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionCancelledException;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContext;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContextService;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionPolicyException;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationDeadlineExceededException;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationStageAdmissionProperties;
import com.rush.rushaicodemother.orchestration.runtime.model.GenerationSynchronousModelCallSupervisor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * 同步编辑模型调用的任务感知边界。
 *
 * <p>所有托管编辑调用都通过此类，因此模型调用无法静默逃脱
 * 任务截止日期、模型尝试预算、取消状态或来源上下文。遗产
 * 调用者仍可以显式使用 {@link #invokeLegacy(String, String)}。</p>
 */
@Service
@RequiredArgsConstructor
public class GenerationEditModelInvoker {

    private final AiCodeEditServiceFactory aiCodeEditServiceFactory;
    private final GenerationExecutionContextService executionContextService;
    private final GenerationPerformanceMonitorService performanceMonitorService;
    private final GenerationSynchronousModelCallSupervisor modelCallSupervisor;
    private final GenerationStageAdmissionProperties stageAdmissionProperties;

    /** 在持久任务执行策略下调用一次编辑模型尝试。 */
    public EditResult invokeManaged(String taskId,
                                    String operation,
                                    String userMessage,
                                    String projectContext) {
        GenerationExecutionContext context = executionContextService.getByTaskId(taskId)
                .orElseThrow(() -> new GenerationExecutionPolicyException(
                        "managed edit model call has no active execution context, taskId=" + taskId));
        String stage = normalizeStage(operation);
        GenerationPerformanceMonitorService.SpanTimer span = performanceMonitorService.startSpan(
                taskId, "edit_model_" + stage, GenerationSpanCategory.MODEL);
        Instant startedAt = Instant.now();
        MonitorContext previousContext = MonitorContextHolder.getContext();
        int attempt = 0;
        // 将可能失败的操作收敛在统一异常边界内，便于清理资源和转换错误。
        try {
            context.assertCanContinue();
            if (Thread.currentThread().isInterrupted()) {
                throw new GenerationExecutionCancelledException("worker_interrupted");
            }
            requireRetryWindow(context, stage);
            attempt = reserveModelBudgets(context, stage);
            Duration timeout = context.clampTimeout(context.limits().modelCallTimeout());
            bindMonitorContext(context);

            AiCodeEditService service = aiCodeEditServiceFactory.createExecutionAiCodeEditService(
                    timeout,
                    () -> context.consume(GenerationBudgetKind.MODEL_TURN),
                    () -> context.consume(GenerationBudgetKind.PROVIDER_FAILOVER_ATTEMPT)
            );
            EditResult result = modelCallSupervisor.execute(
                    context,
                    () -> service.editCode(userMessage, projectContext));
            context.assertCanContinue();
            Duration firstSignalLatency = Duration.between(startedAt, Instant.now());
            long firstSignalLatencyMs = Math.max(1L, firstSignalLatency.toMillis());
            performanceMonitorService.recordSpan(
                    taskId,
                    "model_time_to_first_signal",
                    GenerationSpanCategory.MODEL,
                    "success",
                    firstSignalLatency,
                    "edit_model_" + stage
            );
            performanceMonitorService.recordRuntimeTelemetry(
                    taskId, Map.of("firstTokenLatencyMs", firstSignalLatencyMs));
            span.close("success", "attempt=" + attempt + ",timeoutMs=" + timeout.toMillis());
            return result;
        } catch (RuntimeException | Error failure) {
            span.failed(GenerationErrorClassifier.classify(failure).category());
            throw failure;
        } finally {
            restoreMonitorContext(previousContext);
        }
    }

    /**
     * 执行可选编辑修复。只有有限预算耗尽会返回空结果，取消、超时和上下文错误仍保持上抛。
     */
    public EditResult invokeManagedRepair(String taskId,
                                          String operation,
                                          String userMessage,
                                          String projectContext) {
        String stage = normalizeStage(operation);
        if (!isRepairStage(stage)) {
            throw new IllegalArgumentException("可选修复调用必须使用 retry 或 repair 阶段标识");
        }
        try {
            return invokeManaged(taskId, stage, userMessage, projectContext);
        } catch (GenerationBudgetExceededException budgetExhausted) {
            return null;
        }
    }

    /**
     * 故意位于持久任务运行时之外的调用者的兼容路径。
     * 托管管道有意不使用它。
     */
    public EditResult invokeLegacy(String userMessage, String projectContext) {
        return aiCodeEditServiceFactory.createAiCodeEditService().editCode(userMessage, projectContext);
    }

    private void bindMonitorContext(GenerationExecutionContext context) {
        MonitorContextHolder.setContext(MonitorContext.builder()
                .userId(stringValue(context.userId(), "anonymous"))
                .appId(stringValue(context.appId(), "none"))
                .taskId(context.taskId())
                .build());
    }

    private void restoreMonitorContext(MonitorContext previousContext) {
        if (previousContext == null) {
            MonitorContextHolder.clearContext();
        } else {
            MonitorContextHolder.setContext(previousContext);
        }
    }

    private String normalizeStage(String operation) {
        if (operation == null || operation.isBlank()) {
            return "call";
        }
        String normalized = operation.trim().replaceAll("[^A-Za-z0-9_.-]", "_");
        return normalized.length() <= 64 ? normalized : normalized.substring(0, 64);
    }

    /** 校验并返回有效的重试窗口。 */
    private void requireRetryWindow(GenerationExecutionContext context, String stage) {
        if (!isRepairStage(stage)) {
            return;
        }
        Duration required = stageAdmissionProperties.getRepairModelMinimum()
                .plus(stageAdmissionProperties.getTerminalizationReserve());
        if (!context.hasRemainingTime(required)) {
            throw new GenerationDeadlineExceededException(
                    context.taskId(), "edit_model_" + stage,
                    context.remainingDuration(), required);
        }
    }

    private int reserveModelBudgets(GenerationExecutionContext context, String stage) {
        if (!isRepairStage(stage)) {
            return context.consume(GenerationBudgetKind.ROOT_MODEL_ATTEMPT);
        }
        requireRemainingBudget(context, GenerationBudgetKind.REPAIR_ROUND);
        requireRemainingBudget(context, GenerationBudgetKind.ROOT_MODEL_ATTEMPT);
        context.consume(GenerationBudgetKind.REPAIR_ROUND);
        return context.consume(GenerationBudgetKind.ROOT_MODEL_ATTEMPT);
    }

    private void requireRemainingBudget(GenerationExecutionContext context, GenerationBudgetKind kind) {
        context.assertCanContinue();
        if (!context.hasRemainingBudget(kind)) {
            throw new GenerationBudgetExceededException(kind, context.limit(kind));
        }
    }

    private boolean isRepairStage(String stage) {
        return stage.contains("retry") || stage.contains("repair");
    }

    private String stringValue(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }
}
