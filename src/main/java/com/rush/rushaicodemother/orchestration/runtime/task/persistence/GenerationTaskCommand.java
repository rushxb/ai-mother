package com.rush.rushaicodemother.orchestration.runtime.task.persistence;

import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.GenerationPlanningVariant;
import com.rush.rushaicodemother.orchestration.GenerationResourceRequirements;
import com.rush.rushaicodemother.orchestration.GenerationTaskRequest;
import com.rush.rushaicodemother.orchestration.intent.IntentProfile;
import com.rush.rushaicodemother.orchestration.pipeline.GenerationPipelineRequest;
import com.rush.rushaicodemother.orchestration.plan.GenerationExecutionPlan;
import com.rush.rushaicodemother.orchestration.router.ExpectedValidationLevel;
import com.rush.rushaicodemother.orchestration.router.FallbackPolicy;
import com.rush.rushaicodemother.orchestration.router.GenerationMode;
import com.rush.rushaicodemother.orchestration.router.GenerationModeDecision;
import com.rush.rushaicodemother.orchestration.router.GenerationRoutingDecisionCode;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationSlaEnvelope;
import com.rush.rushaicodemother.orchestration.runtime.tracing.GenerationTraceContext;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;

import java.time.Instant;
import java.util.Objects;

/** 在任何工作实例上重建生成工作所需的不可变命令。 */
public record GenerationTaskCommand(
        int schemaVersion,
        String taskId,
        Long appId,
        Long userId,
        Long tenantId,
        String userPrompt,
        CodeGenTypeEnum codeGenType,
        GenerationMode mode,
        double routingConfidence,
        String routingReason,
        FallbackPolicy fallbackPolicy,
        ExpectedValidationLevel expectedValidationLevel,
        String fallbackReason,
        GenerationRoutingDecisionCode routingDecisionCode,
        GenerationSlaEnvelope slaEnvelope,
        GenerationTraceContext traceContext,
        Instant submittedAt,
        Instant deadlineAt,
        GenerationResourceRequirements resourceRequirements,
        IntentProfile intentProfile,
        GenerationExecutionPlan executionPlan,
        GenerationPlanningVariant planningVariant
) {

    public static final int CURRENT_SCHEMA_VERSION = 8;
    public static final int MIN_SUPPORTED_SCHEMA_VERSION = 1;

    /** 创建生成任务命令实例并完成必要的依赖和初始状态设置。 */
    public GenerationTaskCommand {
        // 先处理前置条件和快速返回分支，避免无效输入进入核心流程。
        if (!supportsSchemaVersion(schemaVersion)) {
            throw new IllegalArgumentException("unsupported generation task command schema");
        }
        if (taskId == null || !taskId.matches("[A-Za-z0-9_-]{1,128}")) {
            throw new IllegalArgumentException("taskId format is invalid");
        }
        requirePositive(appId, "appId");
        requirePositive(userId, "userId");
        if (schemaVersion >= 4) {
            requirePositive(tenantId, "tenantId");
        } else if (tenantId != null) {
            requirePositive(tenantId, "tenantId");
        }
        if (userPrompt == null || userPrompt.isBlank()) {
            throw new IllegalArgumentException("userPrompt cannot be blank");
        }
        Objects.requireNonNull(codeGenType, "codeGenType");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(fallbackPolicy, "fallbackPolicy");
        Objects.requireNonNull(expectedValidationLevel, "expectedValidationLevel");
        if (routingDecisionCode == null) {
            routingDecisionCode = GenerationRoutingDecisionCode.UNKNOWN;
        }
        if (traceContext == null) {
            traceContext = GenerationTraceContext.empty();
        }
        Objects.requireNonNull(submittedAt, "submittedAt");
        Objects.requireNonNull(deadlineAt, "deadlineAt");
        if (!deadlineAt.isAfter(submittedAt)) {
            throw new IllegalArgumentException("deadlineAt must be after submittedAt");
        }
        if (slaEnvelope != null && !deadlineAt.equals(slaEnvelope.totalDeadline(submittedAt))) {
            throw new IllegalArgumentException("deadlineAt does not match the persisted SLA envelope");
        }
        routingConfidence = Math.max(0.0, Math.min(1.0, routingConfidence));
        routingReason = normalize(routingReason, "路由原因未知");
        fallbackReason = normalize(fallbackReason, "");
        if (resourceRequirements == null) {
            resourceRequirements = GenerationResourceRequirements.none();
        }
        if (intentProfile == null) {
            intentProfile = IntentProfile.unknown();
        }
        if (planningVariant == null) {
            planningVariant = GenerationPlanningVariant.CURRENT_DAG;
        }
        if (executionPlan != null) {
            GenerationModeDecision persistedDecision = new GenerationModeDecision(
                    mode, routingConfidence, routingReason, fallbackPolicy, expectedValidationLevel,
                    fallbackReason, routingDecisionCode);
            if (!executionPlan.route().equals(persistedDecision)) {
                throw new IllegalArgumentException("执行计划路由与命令路由不一致");
            }
            if (!executionPlan.sla().equals(slaEnvelope)) {
                throw new IllegalArgumentException("执行计划 SLA 与命令 SLA 不一致");
            }
        }
    }

    /** 兼容尚未声明规划消融方案的完整命令构造入口。 */
    public GenerationTaskCommand(int schemaVersion,
                                 String taskId,
                                 Long appId,
                                 Long userId,
                                 Long tenantId,
                                 String userPrompt,
                                 CodeGenTypeEnum codeGenType,
                                 GenerationMode mode,
                                 double routingConfidence,
                                 String routingReason,
                                 FallbackPolicy fallbackPolicy,
                                 ExpectedValidationLevel expectedValidationLevel,
                                 String fallbackReason,
                                 GenerationRoutingDecisionCode routingDecisionCode,
                                 GenerationSlaEnvelope slaEnvelope,
                                 GenerationTraceContext traceContext,
                                 Instant submittedAt,
                                 Instant deadlineAt,
                                 GenerationResourceRequirements resourceRequirements,
                                 IntentProfile intentProfile,
                                 GenerationExecutionPlan executionPlan) {
        this(schemaVersion, taskId, appId, userId, tenantId, userPrompt, codeGenType, mode,
                routingConfidence, routingReason, fallbackPolicy, expectedValidationLevel,
                fallbackReason, routingDecisionCode, slaEnvelope, traceContext, submittedAt,
                deadlineAt, resourceRequirements, intentProfile, executionPlan,
                GenerationPlanningVariant.CURRENT_DAG);
    }

    /** 兼容尚未持久化意图画像的旧调用方。 */
    public GenerationTaskCommand(int schemaVersion,
                                 String taskId,
                                 Long appId,
                                 Long userId,
                                 Long tenantId,
                                 String userPrompt,
                                 CodeGenTypeEnum codeGenType,
                                 GenerationMode mode,
                                 double routingConfidence,
                                 String routingReason,
                                 FallbackPolicy fallbackPolicy,
                                 ExpectedValidationLevel expectedValidationLevel,
                                 String fallbackReason,
                                 GenerationRoutingDecisionCode routingDecisionCode,
                                 GenerationSlaEnvelope slaEnvelope,
                                 GenerationTraceContext traceContext,
                                 Instant submittedAt,
                                 Instant deadlineAt,
                                 GenerationResourceRequirements resourceRequirements) {
        this(schemaVersion, taskId, appId, userId, tenantId, userPrompt, codeGenType, mode,
                routingConfidence, routingReason, fallbackPolicy, expectedValidationLevel,
                fallbackReason, routingDecisionCode, slaEnvelope, traceContext, submittedAt,
                deadlineAt, resourceRequirements, IntentProfile.unknown(), null,
                GenerationPlanningVariant.CURRENT_DAG);
    }
    /** 兼容尚未持久化资源需求的旧调用方。 */
    public GenerationTaskCommand(int schemaVersion,
                                 String taskId,
                                 Long appId,
                                 Long userId,
                                 Long tenantId,
                                 String userPrompt,
                                 CodeGenTypeEnum codeGenType,
                                 GenerationMode mode,
                                 double routingConfidence,
                                 String routingReason,
                                 FallbackPolicy fallbackPolicy,
                                 ExpectedValidationLevel expectedValidationLevel,
                                 String fallbackReason,
                                 GenerationRoutingDecisionCode routingDecisionCode,
                                 GenerationSlaEnvelope slaEnvelope,
                                 GenerationTraceContext traceContext,
                                 Instant submittedAt,
                                 Instant deadlineAt) {
        this(schemaVersion, taskId, appId, userId, tenantId, userPrompt, codeGenType, mode,
                routingConfidence, routingReason, fallbackPolicy, expectedValidationLevel,
                fallbackReason, routingDecisionCode, slaEnvelope, traceContext, submittedAt,
                deadlineAt, GenerationResourceRequirements.none(), IntentProfile.unknown(), null,
                GenerationPlanningVariant.CURRENT_DAG);
    }

    /** 在特定于路由的 SLA 信封之前创建的命令的兼容性构造函数。 */
    public GenerationTaskCommand(int schemaVersion,
                                 String taskId,
                                 Long appId,
                                 Long userId,
                                 Long tenantId,
                                 String userPrompt,
                                 CodeGenTypeEnum codeGenType,
                                 GenerationMode mode,
                                 double routingConfidence,
                                 String routingReason,
                                 FallbackPolicy fallbackPolicy,
                                 ExpectedValidationLevel expectedValidationLevel,
                                 String fallbackReason,
                                 GenerationRoutingDecisionCode routingDecisionCode,
                                 GenerationSlaEnvelope slaEnvelope,
                                 Instant submittedAt,
                                 Instant deadlineAt) {
        this(schemaVersion, taskId, appId, userId, tenantId, userPrompt, codeGenType, mode,
                routingConfidence, routingReason, fallbackPolicy, expectedValidationLevel,
                fallbackReason, routingDecisionCode, slaEnvelope, GenerationTraceContext.empty(),
                submittedAt, deadlineAt);
    }

    /** 早于租户身份的架构版本 1-3 的兼容性构造函数。 */
    public GenerationTaskCommand(int schemaVersion,
                                 String taskId,
                                 Long appId,
                                 Long userId,
                                 String userPrompt,
                                 CodeGenTypeEnum codeGenType,
                                 GenerationMode mode,
                                 double routingConfidence,
                                 String routingReason,
                                 FallbackPolicy fallbackPolicy,
                                 ExpectedValidationLevel expectedValidationLevel,
                                 String fallbackReason,
                                 GenerationRoutingDecisionCode routingDecisionCode,
                                 GenerationSlaEnvelope slaEnvelope,
                                 Instant submittedAt,
                                 Instant deadlineAt) {
        this(schemaVersion, taskId, appId, userId, null, userPrompt, codeGenType, mode,
                routingConfidence, routingReason, fallbackPolicy, expectedValidationLevel,
                fallbackReason, routingDecisionCode, slaEnvelope, GenerationTraceContext.empty(),
                submittedAt, deadlineAt);
    }

    public GenerationTaskCommand(int schemaVersion,
                                 String taskId,
                                 Long appId,
                                 Long userId,
                                 Long tenantId,
                                 String userPrompt,
                                 CodeGenTypeEnum codeGenType,
                                 GenerationMode mode,
                                 double routingConfidence,
                                 String routingReason,
                                 FallbackPolicy fallbackPolicy,
                                 ExpectedValidationLevel expectedValidationLevel,
                                 String fallbackReason,
                                 Instant submittedAt,
                                 Instant deadlineAt) {
        this(schemaVersion, taskId, appId, userId, tenantId, userPrompt, codeGenType, mode,
                routingConfidence, routingReason, fallbackPolicy, expectedValidationLevel,
                fallbackReason, GenerationRoutingDecisionCode.UNKNOWN, null,
                GenerationTraceContext.empty(), submittedAt, deadlineAt);
    }

    /** 早于租户身份的架构版本 1-3 的兼容性构造函数。 */
    public GenerationTaskCommand(int schemaVersion,
                                 String taskId,
                                 Long appId,
                                 Long userId,
                                 String userPrompt,
                                 CodeGenTypeEnum codeGenType,
                                 GenerationMode mode,
                                 double routingConfidence,
                                 String routingReason,
                                 FallbackPolicy fallbackPolicy,
                                 ExpectedValidationLevel expectedValidationLevel,
                                 String fallbackReason,
                                 Instant submittedAt,
                                 Instant deadlineAt) {
        this(schemaVersion, taskId, appId, userId, null, userPrompt, codeGenType, mode,
                routingConfidence, routingReason, fallbackPolicy, expectedValidationLevel,
                fallbackReason, GenerationRoutingDecisionCode.UNKNOWN, null,
                GenerationTraceContext.empty(), submittedAt, deadlineAt);
    }

    /**
 * 根据输入数据创建当前对象。
 *
 * @param taskId 任务编号
 * @param request 请求参数
 * @param submittedAt {@code submittedAt} 对应的调用参数
 * @param slaEnvelope {@code slaEnvelope} 对应的调用参数
 * @return 生成任务命令
 */
    public static GenerationTaskCommand from(String taskId,
                                             GenerationPipelineRequest request,
                                             Instant submittedAt,
                                             GenerationSlaEnvelope slaEnvelope) {
        return from(taskId, request, submittedAt, slaEnvelope, GenerationTraceContext.empty());
    }

    /**
 * 根据输入数据创建当前对象。
 *
 * @param taskId 任务编号
 * @param request 请求参数
 * @param submittedAt {@code submittedAt} 对应的调用参数
 * @param slaEnvelope {@code slaEnvelope} 对应的调用参数
 * @param traceContext 追踪上下文
 * @return 生成任务命令
 */
    public static GenerationTaskCommand from(String taskId,
                                             GenerationPipelineRequest request,
                                             Instant submittedAt,
                                             GenerationSlaEnvelope slaEnvelope,
                                             GenerationTraceContext traceContext) {
        Objects.requireNonNull(request, "request");
        GenerationTaskRequest taskRequest = Objects.requireNonNull(request.taskRequest(), "taskRequest");
        GenerationModeDecision decision = Objects.requireNonNull(request.modeDecision(), "modeDecision");
        App app = Objects.requireNonNull(taskRequest.app(), "app");
        User user = Objects.requireNonNull(taskRequest.loginUser(), "loginUser");
        GenerationSlaEnvelope envelope = Objects.requireNonNull(slaEnvelope, "slaEnvelope");
        return new GenerationTaskCommand(
                CURRENT_SCHEMA_VERSION,
                taskId,
                app.getId(),
                user.getId(),
                app.getTenantId(),
                taskRequest.message(),
                request.codeGenType(),
                decision.mode(),
                decision.confidence(),
                decision.reason(),
                decision.fallbackPolicy(),
                decision.expectedValidationLevel(),
                decision.fallbackReason(),
                decision.decisionCode(),
                envelope,
                traceContext,
                submittedAt,
                envelope.totalDeadline(submittedAt),
                taskRequest.resourceRequirements(),
                request.intentProfile(),
                request.executionPlan(),
                taskRequest.planningVariant()
        );
    }

    /**
 * 返回模式决策。
 *
 * @return 生成任务命令
 */
    public GenerationModeDecision modeDecision() {
        return new GenerationModeDecision(
                mode,
                routingConfidence,
                routingReason,
                fallbackPolicy,
                expectedValidationLevel,
                fallbackReason,
                routingDecisionCode
        );
    }

    /**
 * 返回恢复。
 *
 * @param app 应用
 * @param user 用户
 * @param workspace 工作区
 * @return 生成任务命令
 */
    public GenerationPipelineRequest restore(App app,
                                             User user,
                                             GenerationWorkspace workspace) {
        if (app == null || user == null || workspace == null
                || !Objects.equals(app.getId(), appId)
                || !Objects.equals(user.getId(), userId)
                || (schemaVersion >= 4 && !Objects.equals(app.getTenantId(), tenantId))
                || !Objects.equals(workspace.appId(), appId)
                || workspace.codeGenType() != codeGenType) {
            throw new IllegalArgumentException("generation task command restore identity mismatch");
        }
        return new GenerationPipelineRequest(
                new GenerationTaskRequest(app, userPrompt, user, resourceRequirements, planningVariant),
                codeGenType,
                workspace,
                intentProfile,
                modeDecision(),
                executionPlan,
                null
        );
    }

    public String route() {
        return mode.route();
    }

    /**
 * 返回{@code supports}结构版本。
 *
 * @param schemaVersion 结构版本
 * @return 满足条件时返回 {@code true}，否则返回 {@code false}
 */
    public static boolean supportsSchemaVersion(Integer schemaVersion) {
        return schemaVersion != null
                && schemaVersion >= MIN_SUPPORTED_SCHEMA_VERSION
                && schemaVersion <= CURRENT_SCHEMA_VERSION;
    }

    private static void requirePositive(Long value, String field) {
        if (value == null || value <= 0) {
            throw new IllegalArgumentException(field + " must be positive");
        }
    }

    private static String normalize(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
