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
import java.util.Objects;

/**
 * 仅当任务仍然可以完成其所需的下游工作时才承认昂贵的阶段。
 *
 * <p> 配置的值是最小有用窗口，而不是操作超时。实际模型、构建和
 * 开发服务器调用保持独立地限制在绝对任务截止日期内。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GenerationStageAdmissionService {

    private static final String REASON_INSUFFICIENT_TIME = "insufficient_remaining_time";
    private static final String REASON_COMPLETION_WINDOW_RESERVED = "completion_window_reserved";

    private final GenerationStageAdmissionProperties properties;
    private final GenerationOrchestrationMetricsCollector metricsCollector;
    private final GenerationPerformanceMonitorService performanceMonitorService;

    /** 当强制构建及其下游验证无法适应时，会快速失败。 */
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

    /** 当运行时验证会消耗为终端化保留的时间时，会快速失败。 */
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

    /** 当完整的维修周期无法容纳时，返回 false，不消耗维修预算。 */
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

    /** 用于保持流式传输 willAutoRepair 标志真实的无副作用检查。 */
    public boolean canRepair(GenerationSession session, GenerationPreparation preparation) {
        return evaluateRepair(session, preparation).admitted();
    }

    /**
     * 在模型回合开始前原子消费回合预算，并返回受完成窗口约束的单回合超时。
     */
    public ModelTurnWindow requireModelTurn(GenerationExecutionContext context,
                                            CodeGenTypeEnum targetType,
                                            String orchestrationMode) {
        ModelTurnDecision decision = evaluateModelTurn(context, targetType);
        if (!decision.admitted()) {
            recordModelTurnReservation(context, orchestrationMode, decision);
            throw decision.toException(context.taskId());
        }
        context.consume(GenerationBudgetKind.MODEL_TURN);
        return decision.window();
    }

    /**
     * 为根模型尝试计算共享墙钟窗口，但不消费逻辑模型回合预算。
     */
    public ModelTurnWindow requireModelAttemptWindow(GenerationExecutionContext context,
                                                     CodeGenTypeEnum targetType,
                                                     String orchestrationMode) {
        ModelTurnDecision decision = evaluateModelTurn(context, targetType);
        if (!decision.admitted()) {
            recordModelTurnReservation(context, orchestrationMode, decision);
            throw decision.toException(context.taskId());
        }
        return decision.window();
    }

    /** 记录根模型墙钟窗口已经抵达受保护的完成边界。 */
    public GenerationModelTurnAdmissionException completionWindowReached(
            GenerationExecutionContext context,
            String orchestrationMode,
            ModelTurnWindow window) {
        Objects.requireNonNull(context, "模型完成窗口必须绑定生成任务上下文");
        Objects.requireNonNull(window, "模型完成窗口不能为空");
        Duration remaining = context.remainingDuration();
        Duration required = window.minimumRequired();
        Duration reserve = window.completionReserve();
        ModelTurnDecision decision = ModelTurnDecision.rejected(
                remaining,
                required,
                reserve,
                window.timeout()
        );
        recordModelTurnReservation(context, orchestrationMode, decision);
        return decision.toException(context.taskId());
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

    private ModelTurnDecision evaluateModelTurn(GenerationExecutionContext context,
                                                CodeGenTypeEnum targetType) {
        if (context == null) {
            throw new IllegalArgumentException("模型回合准入必须绑定生成任务上下文");
        }
        context.assertCanContinue();
        Duration completionReserve = properties.modelCompletionReserve(targetType);
        Duration minimumRequired = properties.modelTurnMinimumRequired(targetType);
        Duration remaining = context.remainingDuration();
        Duration availableForModel = remaining.minus(completionReserve);
        if (availableForModel.isNegative()) {
            availableForModel = Duration.ZERO;
        }
        Duration requested = context.limits().modelCallTimeout();
        Duration timeout = minimum(requested, availableForModel);
        boolean admitted = remaining.compareTo(minimumRequired) >= 0;
        boolean completionWindowLimited = admitted && requested.compareTo(availableForModel) > 0;
        return new ModelTurnDecision(
                admitted,
                remaining,
                minimumRequired,
                completionReserve,
                timeout,
                completionWindowLimited
        );
    }

    private Duration minimum(Duration first, Duration second) {
        return first.compareTo(second) <= 0 ? first : second;
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

    private void recordModelTurnReservation(GenerationExecutionContext context,
                                            String orchestrationMode,
                                            ModelTurnDecision decision) {
        metricsCollector.recordStageAdmission(orchestrationMode, "model_turn", "reserved_completion");
        String detail = "reason=" + REASON_COMPLETION_WINDOW_RESERVED
                + ",remainingMs=" + decision.remaining().toMillis()
                + ",requiredMs=" + decision.minimumRequired().toMillis()
                + ",completionReserveMs=" + decision.completionReserve().toMillis();
        performanceMonitorService.recordSpan(
                context == null ? null : context.taskId(),
                "model_turn_admission_reserved",
                GenerationSpanCategory.MODEL,
                "reserved_completion",
                Duration.ZERO,
                detail
        );
        log.info("模型回合准入停止，taskId={}, remainingMs={}, requiredMs={}, completionReserveMs={}",
                context == null ? null : context.taskId(),
                decision.remaining().toMillis(),
                decision.minimumRequired().toMillis(),
                decision.completionReserve().toMillis());
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

    /** 模型调用可使用的墙钟窗口以及被保护的后续完成窗口。 */
    public record ModelTurnWindow(Duration timeout,
                                  Duration completionReserve,
                                  Duration minimumRequired,
                                  boolean completionWindowLimited) {

        public ModelTurnWindow {
            if (timeout == null || timeout.isZero() || timeout.isNegative()) {
                throw new IllegalArgumentException("模型回合超时必须大于 0");
            }
            if (completionReserve == null || completionReserve.isZero() || completionReserve.isNegative()) {
                throw new IllegalArgumentException("模型完成预留必须大于 0");
            }
            if (minimumRequired == null || minimumRequired.compareTo(completionReserve) <= 0) {
                throw new IllegalArgumentException("模型回合最小窗口必须大于完成预留");
            }
        }
    }

    private record ModelTurnDecision(boolean admitted,
                                     Duration remaining,
                                     Duration minimumRequired,
                                     Duration completionReserve,
                                     Duration timeout,
                                     boolean completionWindowLimited) {

        private static ModelTurnDecision rejected(Duration remaining,
                                                  Duration minimumRequired,
                                                  Duration completionReserve,
                                                  Duration timeout) {
            return new ModelTurnDecision(
                    false,
                    nonNegative(remaining),
                    nonNegative(minimumRequired),
                    nonNegative(completionReserve),
                    nonNegative(timeout),
                    true
            );
        }

        private ModelTurnWindow window() {
            return new ModelTurnWindow(
                    timeout,
                    completionReserve,
                    minimumRequired,
                    completionWindowLimited
            );
        }

        private GenerationModelTurnAdmissionException toException(String taskId) {
            return new GenerationModelTurnAdmissionException(
                    taskId,
                    remaining,
                    minimumRequired,
                    completionReserve
            );
        }

        private static Duration nonNegative(Duration value) {
            return value == null || value.isNegative() ? Duration.ZERO : value;
        }
    }
}
