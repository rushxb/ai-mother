package com.rush.rushaicodemother.orchestration.runtime.task.persistence;

import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.GenerationTaskRequest;
import com.rush.rushaicodemother.orchestration.pipeline.GenerationPipelineRequest;
import com.rush.rushaicodemother.orchestration.router.ExpectedValidationLevel;
import com.rush.rushaicodemother.orchestration.router.FallbackPolicy;
import com.rush.rushaicodemother.orchestration.router.GenerationMode;
import com.rush.rushaicodemother.orchestration.router.GenerationModeDecision;
import com.rush.rushaicodemother.orchestration.router.GenerationRoutingDecisionCode;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationSlaEnvelope;
import com.rush.rushaicodemother.orchestration.runtime.tracing.GenerationTraceContext;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.entity.User;

import java.time.Instant;
import java.util.Objects;

/** Immutable command required to reconstruct generation work on any worker instance. */
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
        Instant deadlineAt
) {

    public static final int CURRENT_SCHEMA_VERSION = 4;
    public static final int MIN_SUPPORTED_SCHEMA_VERSION = 1;

    public GenerationTaskCommand {
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
        routingReason = normalize(routingReason, "router_reason_unknown");
        fallbackReason = normalize(fallbackReason, "");
    }

    /** Compatibility constructor for commands created before route-specific SLA envelopes. */
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

    /** Compatibility constructor for schema versions 1-3 that predate tenant identity. */
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

    /** Compatibility constructor for schema versions 1-3 that predate tenant identity. */
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

    public static GenerationTaskCommand from(String taskId,
                                             GenerationPipelineRequest request,
                                             Instant submittedAt,
                                             GenerationSlaEnvelope slaEnvelope) {
        return from(taskId, request, submittedAt, slaEnvelope, GenerationTraceContext.empty());
    }

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
                envelope.totalDeadline(submittedAt)
        );
    }

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
                new GenerationTaskRequest(app, userPrompt, user),
                codeGenType,
                workspace,
                modeDecision()
        );
    }

    public String route() {
        return mode.route();
    }

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
