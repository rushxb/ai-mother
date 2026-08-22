package com.rush.rushaicodemother.orchestration.decision;

import com.rush.rushaicodemother.config.AiModelRuntimeProperties;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.model.enums.ModelInvocationBillingMode;
import com.rush.rushaicodemother.model.enums.ModelInvocationPurpose;
import com.rush.rushaicodemother.monitor.MonitorContext;
import com.rush.rushaicodemother.monitor.MonitorContextHolder;
import com.rush.rushaicodemother.orchestration.GenerationTaskRequest;
import com.rush.rushaicodemother.orchestration.intent.IntentClarificationRefiner;
import com.rush.rushaicodemother.orchestration.intent.IntentProfile;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationBudgetKind;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContext;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionLimits;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionPolicyException;
import com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskAdmissionService;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 在最终场景决策冻结前完成一次有界澄清的 deep module。
 *
 * <p>该 module 先通过用户与租户 preflight gate，再绑定可计费 task identity，
 * 最多执行一次逻辑模型回合。画像精化发生在路由之前，因此 route、资源、权限、
 * SLA 与验证图仍由同一最终决策派生。</p>
 */
@Component
@Slf4j
public class GenerationScenarioPreflight {

    private static final Duration PREFLIGHT_MINIMUM_OPERATION_TIMEOUT = Duration.ofMillis(100);
    private static final Duration PREFLIGHT_DEADLINE_RESERVE = Duration.ofSeconds(1);

    private final GenerationScenarioDecisionKernel scenarioDecisionKernel;
    private final IntentClarificationRefiner clarificationRefiner;
    private final GenerationTaskAdmissionService admissionService;
    private final AiModelRuntimeProperties runtimeProperties;
    private final Clock clock;

    @Autowired
    public GenerationScenarioPreflight(GenerationScenarioDecisionKernel scenarioDecisionKernel,
                                       IntentClarificationRefiner clarificationRefiner,
                                       GenerationTaskAdmissionService admissionService,
                                       AiModelRuntimeProperties runtimeProperties) {
        this(scenarioDecisionKernel, clarificationRefiner, admissionService,
                runtimeProperties, Clock.systemUTC());
    }

    GenerationScenarioPreflight(GenerationScenarioDecisionKernel scenarioDecisionKernel,
                                IntentClarificationRefiner clarificationRefiner,
                                GenerationTaskAdmissionService admissionService,
                                AiModelRuntimeProperties runtimeProperties,
                                Clock clock) {
        this.scenarioDecisionKernel = Objects.requireNonNull(
                scenarioDecisionKernel, "场景决策内核不能为空");
        this.clarificationRefiner = Objects.requireNonNull(
                clarificationRefiner, "意图澄清模块不能为空");
        this.admissionService = Objects.requireNonNull(
                admissionService, "任务准入模块不能为空");
        this.runtimeProperties = Objects.requireNonNull(
                runtimeProperties, "模型运行配置不能为空");
        this.clock = Objects.requireNonNull(clock, "业务时钟不能为空");
    }

    public GenerationScenarioPreflightResult prepare(String taskId,
                                                     Instant submittedAt,
                                                     GenerationTaskRequest request,
                                                     CodeGenTypeEnum targetType,
                                                     GenerationWorkspace workspace) {
        requireTaskIdentity(taskId, submittedAt, request, targetType, workspace);
        AtomicReference<GenerationExecutionContext> preflightContext = new AtomicReference<>();
        AtomicBoolean creditReserved = new AtomicBoolean(false);
        try {
            GenerationScenarioDecision decision = scenarioDecisionKernel.decide(
                    request,
                    targetType,
                    workspace,
                    profile -> refineIfEligible(
                            taskId, submittedAt, request, targetType, profile,
                            preflightContext, creditReserved));
            GenerationPreflightUsage usage = preflightContext.get() == null
                    ? GenerationPreflightUsage.none()
                    : GenerationPreflightUsage.from(preflightContext.get());
            return new GenerationScenarioPreflightResult(decision, usage, creditReserved.get());
        } catch (RuntimeException preflightFailure) {
            if (creditReserved.get()) {
                try {
                    admissionService.settlePreflightReservation(taskId);
                } catch (RuntimeException compensationFailure) {
                    preflightFailure.addSuppressed(compensationFailure);
                }
            }
            throw preflightFailure;
        }
    }

    private IntentProfile refineIfEligible(String taskId,
                                           Instant submittedAt,
                                           GenerationTaskRequest request,
                                           CodeGenTypeEnum targetType,
                                           IntentProfile profile,
                                           AtomicReference<GenerationExecutionContext> contextHolder,
                                           AtomicBoolean creditReserved) {
        if (!clarificationRefiner.canRefine(profile)) {
            return profile;
        }
        admissionService.assertMayPreflight(taskId, request, targetType, profile);
        creditReserved.set(true);
        GenerationExecutionContext context = createContext(taskId, submittedAt, request);
        contextHolder.set(context);
        try {
            return withMonitorContext(request, taskId, () -> clarificationRefiner.refine(
                    profile, request.message(), taskId, context));
        } catch (GenerationExecutionPolicyException preflightBudgetFailure) {
            // preflight 是可选增益；局部截止时保留已消费用量并退回确定性画像。
            log.warn("场景 preflight 达到局部预算或截止时间，沿用本地决策，taskId: {}", taskId);
            return profile;
        }
    }

    private GenerationExecutionContext createContext(String taskId,
                                                     Instant submittedAt,
                                                     GenerationTaskRequest request) {
        Duration modelTimeout = runtimeProperties.getIntentClarificationTimeout();
        if (modelTimeout == null || modelTimeout.isZero() || modelTimeout.isNegative()) {
            throw new IllegalStateException("意图澄清超时必须为正数");
        }
        GenerationExecutionLimits limits = new GenerationExecutionLimits(
                modelTimeout.plus(PREFLIGHT_DEADLINE_RESERVE),
                modelTimeout,
                PREFLIGHT_MINIMUM_OPERATION_TIMEOUT,
                Map.of(
                        GenerationBudgetKind.ROOT_MODEL_ATTEMPT, 1,
                        GenerationBudgetKind.MODEL_TURN, 1,
                        GenerationBudgetKind.PROVIDER_FAILOVER_ATTEMPT, 2,
                        GenerationBudgetKind.TOOL_WRITE, 0,
                        GenerationBudgetKind.BUILD_EXECUTION, 0,
                        GenerationBudgetKind.REPAIR_ROUND, 0));
        return new GenerationExecutionContext(
                taskId,
                request.app().getId(),
                request.loginUser().getId(),
                submittedAt,
                limits,
                clock);
    }

    private IntentProfile withMonitorContext(GenerationTaskRequest request,
                                             String taskId,
                                             java.util.function.Supplier<IntentProfile> action) {
        MonitorContext previous = MonitorContextHolder.getContext();
        MonitorContextHolder.setContext(MonitorContext.builder()
                .userId(request.loginUser().getId().toString())
                .appId(request.app().getId().toString())
                .taskId(taskId)
                .invocationPurpose(ModelInvocationPurpose.GENERATION)
                .billingMode(ModelInvocationBillingMode.BILLABLE)
                .build());
        try {
            return action.get();
        } finally {
            if (previous == null) {
                MonitorContextHolder.clearContext();
            } else {
                MonitorContextHolder.setContext(previous);
            }
        }
    }

    private void requireTaskIdentity(String taskId,
                                     Instant submittedAt,
                                     GenerationTaskRequest request,
                                     CodeGenTypeEnum targetType,
                                     GenerationWorkspace workspace) {
        if (taskId == null || !taskId.matches("[A-Za-z0-9_-]{1,128}")) {
            throw new IllegalArgumentException("preflight taskId 格式无效");
        }
        Objects.requireNonNull(submittedAt, "preflight 提交时间不能为空");
        Objects.requireNonNull(targetType, "preflight 目标工程类型不能为空");
        Objects.requireNonNull(workspace, "preflight 工作区不能为空");
        if (request == null || request.app() == null || request.app().getId() == null
                || request.app().getTenantId() == null
                || request.loginUser() == null || request.loginUser().getId() == null
                || request.message() == null || request.message().isBlank()) {
            throw new IllegalArgumentException("preflight 请求身份不完整");
        }
    }
}
