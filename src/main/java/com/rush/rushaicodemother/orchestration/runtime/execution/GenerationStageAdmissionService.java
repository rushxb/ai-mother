package com.rush.rushaicodemother.orchestration.runtime.execution;

import com.rush.rushaicodemother.core.handler.GenerationStreamEvent;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.monitor.GenerationOrchestrationMetricsCollector;
import com.rush.rushaicodemother.monitor.GenerationPerformanceMonitorService;
import com.rush.rushaicodemother.monitor.span.GenerationSpanCategory;
import com.rush.rushaicodemother.orchestration.GenerationPreparation;
import com.rush.rushaicodemother.orchestration.GenerationSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Admits expensive stages only when the task can still finish their required downstream work.
 *
 * <p>Configured values are minimum useful windows, not operation timeouts. Actual model, build and
 * Dev Server calls remain independently clamped to the absolute task deadline.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GenerationStageAdmissionService {

    private static final String REASON_INSUFFICIENT_TIME = "insufficient_remaining_time";

    private final GenerationStageAdmissionProperties properties;
    private final GenerationOrchestrationMetricsCollector metricsCollector;
    private final GenerationPerformanceMonitorService performanceMonitorService;

    /** Fails fast when a mandatory build plus its downstream validation cannot fit. */
    public void requireBuild(GenerationSession session,
                             GenerationPreparation preparation,
                             String orchestrationMode) {
        Duration required = properties.getBuildMinimum()
                .plus(runtimeWindowIfRequired(preparation))
                .plus(properties.getTerminalizationReserve());
        Decision decision = evaluate(session, "build", required);
        if (decision.admitted()) {
            return;
        }
        recordRejection(session, orchestrationMode, decision, "rejected", GenerationSpanCategory.BUILD, null);
        throw decision.toDeadlineException(taskId(session, preparation));
    }

    /** Fails fast when runtime validation would consume the time reserved for terminalization. */
    public void requireRuntimeValidation(GenerationSession session,
                                         GenerationPreparation preparation,
                                         String orchestrationMode) {
        Duration required = properties.getRuntimeValidationMinimum()
                .plus(properties.getTerminalizationReserve());
        Decision decision = evaluate(session, "runtime_validation", required);
        if (decision.admitted()) {
            return;
        }
        recordRejection(
                session,
                orchestrationMode,
                decision,
                "rejected",
                GenerationSpanCategory.VALIDATION,
                null
        );
        throw decision.toDeadlineException(taskId(session, preparation));
    }

    /** Returns false, without consuming repair budget, when a complete repair cycle cannot fit. */
    public boolean allowRepair(GenerationSession session,
                               GenerationPreparation preparation,
                               String orchestrationMode,
                               String repairStage) {
        Decision decision = evaluateRepair(session, preparation);
        if (decision.admitted()) {
            return true;
        }
        recordRejection(
                session,
                orchestrationMode,
                decision,
                "skipped",
                GenerationSpanCategory.REPAIR,
                repairStage
        );
        return false;
    }

    /** Side-effect-free check used to keep streamed willAutoRepair flags truthful. */
    public boolean canRepair(GenerationSession session, GenerationPreparation preparation) {
        return evaluateRepair(session, preparation).admitted();
    }

    private Decision evaluateRepair(GenerationSession session, GenerationPreparation preparation) {
        Duration required = properties.getRepairModelMinimum()
                .plus(properties.getTerminalizationReserve());
        if (preparation != null && preparation.requiresBuildValidation()) {
            required = required.plus(properties.getBuildMinimum())
                    .plus(runtimeWindowIfRequired(preparation));
        }
        return evaluate(session, "repair", required);
    }

    private Decision evaluate(GenerationSession session, String stage, Duration required) {
        GenerationExecutionContext context = session == null ? null : session.executionContext();
        if (context == null) {
            return Decision.unmanaged(stage, required);
        }
        context.assertCanContinue();
        Duration remaining = context.remainingDuration();
        return new Decision(stage, context.hasRemainingTime(required), remaining, required);
    }

    private Duration runtimeWindowIfRequired(GenerationPreparation preparation) {
        if (preparation == null) {
            return Duration.ZERO;
        }
        CodeGenTypeEnum targetType = preparation.targetType();
        return targetType == CodeGenTypeEnum.VUE_PROJECT || targetType == CodeGenTypeEnum.FULL_STACK_PROJECT
                ? properties.getRuntimeValidationMinimum()
                : Duration.ZERO;
    }

    private void recordRejection(GenerationSession session,
                                 String orchestrationMode,
                                 Decision decision,
                                 String outcome,
                                 GenerationSpanCategory category,
                                 String repairStage) {
        String metricStage = repairStage == null || repairStage.isBlank()
                ? decision.stage()
                : decision.stage() + "_" + repairStage.trim();
        metricsCollector.recordStageAdmission(orchestrationMode, metricStage, outcome);

        String spanStage = "repair".equals(decision.stage())
                ? "repair_admission_skipped"
                : decision.stage() + "_admission_rejected";
        String detail = "reason=" + REASON_INSUFFICIENT_TIME
                + ",remainingMs=" + decision.remaining().toMillis()
                + ",requiredMs=" + decision.required().toMillis()
                + (repairStage == null || repairStage.isBlank() ? "" : ",repairStage=" + repairStage.trim());
        performanceMonitorService.recordSpan(
                session == null ? null : session.taskId(),
                spanStage,
                category,
                outcome,
                Duration.ZERO,
                detail
        );

        if (session != null) {
            Map<String, Object> eventData = new LinkedHashMap<>();
            eventData.put("agent", "DeadlinePolicy");
            eventData.put("stage", decision.stage() + "_admission");
            eventData.put("status", outcome);
            eventData.put("reason", REASON_INSUFFICIENT_TIME);
            eventData.put("remainingMs", decision.remaining().toMillis());
            eventData.put("requiredMs", decision.required().toMillis());
            eventData.put("taskId", session.taskId());
            if (repairStage != null && !repairStage.isBlank()) {
                eventData.put("repairStage", repairStage.trim());
            }
            session.emit(GenerationStreamEvent.agentEvent("", eventData));
        }
        log.info("Generation stage admission denied, taskId={}, stage={}, outcome={}, remainingMs={}, requiredMs={}",
                session == null ? null : session.taskId(), metricStage, outcome,
                decision.remaining().toMillis(), decision.required().toMillis());
    }

    private String taskId(GenerationSession session, GenerationPreparation preparation) {
        if (session != null && session.taskId() != null && !session.taskId().isBlank()) {
            return session.taskId();
        }
        return preparation == null ? "unknown" : preparation.taskId();
    }

    private record Decision(String stage, boolean admitted, Duration remaining, Duration required) {

        private Decision {
            if (stage == null || stage.isBlank()) {
                throw new IllegalArgumentException("generation stage cannot be blank");
            }
            remaining = remaining == null || remaining.isNegative() ? Duration.ZERO : remaining;
            if (required == null || required.isZero() || required.isNegative()) {
                throw new IllegalArgumentException("required stage duration must be greater than zero");
            }
        }

        private static Decision unmanaged(String stage, Duration required) {
            return new Decision(stage, true, required, required);
        }

        private GenerationDeadlineExceededException toDeadlineException(String taskId) {
            return new GenerationDeadlineExceededException(taskId, stage, remaining, required);
        }
    }
}
