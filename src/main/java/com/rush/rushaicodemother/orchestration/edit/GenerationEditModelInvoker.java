package com.rush.rushaicodemother.orchestration.edit;

import com.rush.rushaicodemother.ai.AiCodeEditService;
import com.rush.rushaicodemother.ai.AiCodeEditServiceFactory;
import com.rush.rushaicodemother.ai.model.EditResult;
import com.rush.rushaicodemother.core.error.GenerationErrorClassifier;
import com.rush.rushaicodemother.monitor.MonitorContext;
import com.rush.rushaicodemother.monitor.MonitorContextHolder;
import com.rush.rushaicodemother.monitor.GenerationPerformanceMonitorService;
import com.rush.rushaicodemother.monitor.span.GenerationSpanCategory;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationBudgetKind;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionCancelledException;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContext;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContextService;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionPolicyException;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationDeadlineExceededException;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationStageAdmissionProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Task-aware boundary for synchronous edit-model calls.
 *
 * <p>All managed edit calls pass through this class so a model invocation cannot silently escape
 * the task deadline, model-attempt budget, cancellation state or provenance context. Legacy
 * callers may still use {@link #invokeLegacy(String, String)} explicitly.</p>
 */
@Service
@RequiredArgsConstructor
public class GenerationEditModelInvoker {

    private final AiCodeEditServiceFactory aiCodeEditServiceFactory;
    private final GenerationExecutionContextService executionContextService;
    private final GenerationPerformanceMonitorService performanceMonitorService;
    private final GenerationStageAdmissionProperties stageAdmissionProperties;

    /** Invokes one edit-model attempt under the durable task execution policy. */
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
        MonitorContext previousContext = MonitorContextHolder.getContext();
        int attempt = 0;
        try {
            context.assertCanContinue();
            if (Thread.currentThread().isInterrupted()) {
                throw new GenerationExecutionCancelledException("worker_interrupted");
            }
            requireRetryWindow(context, stage);
            attempt = context.consume(GenerationBudgetKind.MODEL_ATTEMPT);
            Duration timeout = context.clampTimeout(context.limits().modelCallTimeout());
            bindMonitorContext(context);

            AiCodeEditService service = aiCodeEditServiceFactory.createAiCodeEditService(timeout);
            EditResult result = service.editCode(userMessage, projectContext);
            context.assertCanContinue();
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
     * Compatibility path for callers that are deliberately outside the durable task runtime.
     * It is intentionally not used by managed pipelines.
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

    private void requireRetryWindow(GenerationExecutionContext context, String stage) {
        if (!stage.contains("retry") && !stage.contains("repair")) {
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

    private String stringValue(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }
}
