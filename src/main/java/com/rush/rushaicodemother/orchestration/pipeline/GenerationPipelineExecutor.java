package com.rush.rushaicodemother.orchestration.pipeline;

import com.rush.rushaicodemother.core.handler.GenerationStreamEvent;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.infrastructure.diagnostic.LogExceptionSanitizer;
import com.rush.rushaicodemother.memory.GenerationOutcomeMemoryRequest;
import com.rush.rushaicodemother.memory.GenerationOutcomeMemoryService;
import com.rush.rushaicodemother.model.enums.GenerationTaskStatus;
import com.rush.rushaicodemother.monitor.GenerationPerformanceMonitorService;
import com.rush.rushaicodemother.monitor.span.GenerationSpanCategory;
import com.rush.rushaicodemother.orchestration.GenerationSession;
import com.rush.rushaicodemother.orchestration.GenerationSessionRegistry;
import com.rush.rushaicodemother.orchestration.GenerationTerminalOutcome;
import com.rush.rushaicodemother.orchestration.attempt.completion.GenerationCompletionPolicy;
import com.rush.rushaicodemother.orchestration.plan.GenerationExecutionPlan;
import com.rush.rushaicodemother.orchestration.event.GenerationEventPublisher;
import com.rush.rushaicodemother.orchestration.event.GenerationEventType;
import com.rush.rushaicodemother.orchestration.lifecycle.GenerationTaskLifecycleService;
import com.rush.rushaicodemother.orchestration.router.FallbackPolicy;
import com.rush.rushaicodemother.orchestration.router.GenerationMode;
import com.rush.rushaicodemother.orchestration.router.GenerationModeDecision;
import com.rush.rushaicodemother.orchestration.routing.GenerationRoute;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContextService;
import com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskExecution;
import com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskRuntimeLifecycleService;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspaceReleaseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.rush.rushaicodemother.core.error.GenerationErrorClassifier;
import com.rush.rushaicodemother.orchestration.attempt.completion.GenerationCompletionEvidenceType;
import com.rush.rushaicodemother.service.trace.GenerationOutcomeQuality;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 执行提交的生成请求并拥有路由回退和任务运行时最终确定。
 *
 * <p>Pipelines 描述它们是否同步完成，将完成所有权转移给
 * 后台工作，或者需要路由回退。该模块将这些生命周期规则排除在外
 * HTTP控制器和单独的路由适配器。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor(onConstructor_ = @Autowired)
public class GenerationPipelineExecutor {

    private static final int MAX_ROUTE_ATTEMPTS = 2;
    private static final String PIPELINE_FAILURE_REASON = "generation_pipeline_failed";
    private static final String PIPELINE_DEADLINE_REASON = "generation_deadline_exceeded";
    private static final String PIPELINE_CANCELLED_REASON = "generation_cancelled";
    private static final int MAX_FALLBACK_REASON_LENGTH = 300;

    private final List<GenerationPipeline> generationPipelines;
    private final GenerationEventPublisher generationEventPublisher;
    private final GenerationSessionRegistry generationSessionRegistry;
    private final GenerationExecutionContextService generationExecutionContextService;
    private final GenerationTaskRuntimeLifecycleService generationTaskRuntimeLifecycleService;
    private final GenerationPerformanceMonitorService generationPerformanceMonitorService;
    private final GenerationWorkspaceReleaseService workspaceReleaseService;
    private final GenerationTaskLifecycleService generationTaskLifecycleService;
    private final GenerationOutcomeMemoryService generationOutcomeMemoryService;
    private final GenerationCompletionPolicy generationCompletionPolicy;

    /**
 * 执行生成流水线处理流程。
 *
 * @param request 请求参数
 */
    public void execute(GenerationPipelineRequest request) {
        GenerationTaskExecution execution = request.requireExecution();
        // 将可能失败的操作收敛在统一异常边界内，便于清理资源和转换错误。
        try {
            generationTaskRuntimeLifecycleService.activate(execution.executionFence());
            generationPerformanceMonitorService.recordSpan(
                    execution.taskId(),
                    "queue_wait",
                    GenerationSpanCategory.QUEUE,
                    "success",
                    Duration.between(execution.submittedAt(), Instant.now()),
                    request.modeDecision().route()
            );
            GenerationPipelineRequest currentRequest = request;
            for (int attempt = 1; attempt <= MAX_ROUTE_ATTEMPTS; attempt++) {
                execution.session().throwIfCancelled();
                GenerationPipeline pipeline = findPipeline(currentRequest);
                execution.session().recordRoute(pipeline.route());
                GenerationPerformanceMonitorService.SpanTimer routeSpan =
                        generationPerformanceMonitorService.startSpan(
                                execution.taskId(), "pipeline_route_attempt", GenerationSpanCategory.PIPELINE);
                GenerationPipelineOutcome outcome;
                try {
                    outcome = pipeline.execute(currentRequest);
                    if (outcome == null) {
                        throw new IllegalStateException(
                                "generation pipeline returned no outcome: " + pipeline.route());
                    }
                    routeSpan.close(
                            outcome.disposition().name().toLowerCase(java.util.Locale.ROOT),
                            "route=" + pipeline.route() + ",attempt=" + attempt
                    );
                } catch (RuntimeException | Error routeFailure) {
                    routeSpan.failed("route=" + pipeline.route() + ",attempt=" + attempt);
                    throw routeFailure;
                }
                switch (outcome.disposition()) {
                    case COMPLETED -> {
                        completeManagedTask(currentRequest, outcome);
                        return;
                    }
                    case RUNNING -> {
                        return;
                    }
                    case FALLBACK -> currentRequest = fallback(currentRequest, pipeline, outcome.reason());
                }
            }
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "生成管线路由尝试次数已耗尽");
        } catch (RuntimeException failure) {
            failManagedTask(request, failure);
        } catch (Error fatalFailure) {
            try {
                failManagedTask(request, fatalFailure);
            } catch (RuntimeException terminalizationFailure) {
                fatalFailure.addSuppressed(terminalizationFailure);
            }
            throw fatalFailure;
        }
    }

    private GenerationPipeline findPipeline(GenerationPipelineRequest request) {
        return generationPipelines.stream()
                .filter(pipeline -> pipeline.supports(request))
                .findFirst()
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.OPERATION_ERROR,
                        "没有匹配的生成管线: " + request.modeDecision().route()
                ));
    }

    /** 返回回退。 */
    private GenerationPipelineRequest fallback(GenerationPipelineRequest request,
                                               GenerationPipeline failedPipeline,
                                               String pipelineReason) {
        GenerationModeDecision decision = request.modeDecision();
        String failedRoute = failedPipeline == null ? decision.route() : failedPipeline.route();
        if (decision.fallbackPolicy() != FallbackPolicy.ESCALATE_TO_HEAVY_EXPERT
                || request.workspace() == null
                || (!request.workspace().exists() && !GenerationRoute.CREATE.equals(failedRoute))) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "当前生成管线不允许回退: " + failedRoute);
        }
        String reason = normalizeFallbackReason(pipelineReason, failedRoute);
        String transitionMessage = GenerationRoute.CREATE.equals(failedRoute)
                ? "CREATE 快速路径未完成，正在切换专家模式..."
                : "快速生成路径未完成，正在切换专家模式...";
        generationEventPublisher.publishSafely(
                request.taskRequest(),
                GenerationEventType.TASK_ROUTE,
                transitionMessage,
                Map.of(
                        "taskId", request.requireExecution().taskId(),
                        "mode", GenerationMode.HEAVY_EXPERT.name(),
                        "route", GenerationRoute.HEAVY_GENERATION,
                        "routerReason", decision.reason(),
                        "fallbackReason", reason
                )
        );
        request.requireExecution().session().emit(GenerationStreamEvent.generationStage(
                transitionMessage,
                Map.of(
                        "stage", "route_fallback",
                        "taskId", request.requireExecution().taskId(),
                        "fromRoute", failedRoute,
                        "route", GenerationRoute.HEAVY_GENERATION,
                        "reason", reason
                )
        ));
        return request.withModeDecision(decision.withFallback(GenerationMode.HEAVY_EXPERT, reason));
    }

    private String normalizeFallbackReason(String pipelineReason, String failedRoute) {
        if (pipelineReason == null || pipelineReason.isBlank()) {
            return "pipeline_failed_or_unavailable:" + failedRoute;
        }
        String sanitized = LogExceptionSanitizer.sanitizeValue(
                pipelineReason, MAX_FALLBACK_REASON_LENGTH);
        return sanitized.isBlank()
                ? "pipeline_failed_or_unavailable:" + failedRoute
                : sanitized;
    }

    /** 完成{@code Managed}任务并持久化终态。 */
    private void completeManagedTask(GenerationPipelineRequest request,
                                     GenerationPipelineOutcome outcome) {
        GenerationTaskExecution execution = request.requireExecution();
        GenerationSession session = execution.session();
        GenerationTaskStatus status = outcome.terminalStatus();
        if (status == GenerationTaskStatus.SUCCESS) {
            session.throwIfCancelled();
            GenerationExecutionPlan.ValidationGraph validationGraph = request.executionPlan() == null
                    ? GenerationExecutionPlan.ValidationGraph.forLevel(
                            request.modeDecision().expectedValidationLevel())
                    : request.executionPlan().validationGraph();
            generationCompletionPolicy.requireCompletable(
                    session, validationGraph, outcome.completionEvidence());
            workspaceReleaseService.releaseVerified(
                    session,
                    session.executionWorkspace() == null
                            ? request.codeGenType()
                            : session.executionWorkspace().codeGenType()
            );
        }
        GenerationOutcomeQuality outcomeQuality = resolveOutcomeQuality(execution, outcome, null);
        Long appIdentity = request.taskRequest().app().getId();
        // 未采集到任何指标时走既有签名，保持原调用契约不变。
        boolean hasOutcomeQuality = !outcomeQuality.isEmpty();
        if (status == GenerationTaskStatus.SUCCESS) {
            if (hasOutcomeQuality) {
                generationTaskLifecycleService.completeGenerationAndCharge(
                        execution.taskId(), appIdentity, status, null,
                        outcome.resultSummary(), outcomeQuality);
            } else {
                generationTaskLifecycleService.completeGenerationAndCharge(
                        execution.taskId(), appIdentity, status, null, outcome.resultSummary());
            }
        } else {
            if (hasOutcomeQuality) {
                generationTaskLifecycleService.completeGeneration(
                        execution.taskId(), appIdentity, status, outcome.reason(),
                        outcome.resultSummary(), outcomeQuality);
            } else {
                generationTaskLifecycleService.completeGeneration(
                        execution.taskId(), appIdentity, status, outcome.reason(),
                        outcome.resultSummary());
            }
        }
        rememberOutcome(request, status, outcome.resultSummary());
        if (status == GenerationTaskStatus.SUCCESS) {
            generationEventPublisher.publishSafely(
                    request.taskRequest(), GenerationEventType.TASK_DONE, "生成任务已发布", Map.of(
                            "taskId", execution.taskId(),
                            "route", request.modeDecision().route(),
                            "status", status.getValue()
                    ));
        }
        if (session.tryBeginCompletion()) {
            session.complete();
        }
        finalizeRuntime(request, execution, session, status, outcome.reason());
    }

    /** 将{@code Managed}任务标记为失败并记录原因。 */
    private void failManagedTask(GenerationPipelineRequest request, Throwable failure) {
        GenerationTaskExecution execution = request.requireExecution();
        GenerationSession session = execution.session();
        GenerationTerminalOutcome outcome = GenerationTerminalOutcome.resolve(session, failure);
        String terminalReason = terminalReason(outcome);
        String resultSummary = buildFailureResultSummary(request, outcome);
        log.error("Generation pipeline worker failed, taskId: {}, route: {}, status: {}",
                execution.taskId(), request.modeDecision().route(), outcome.status(),
                LogExceptionSanitizer.sanitize(failure));
        if (!session.tryBeginCompletion()) {
            return;
        }
        if (outcome == GenerationTerminalOutcome.CANCELLED) {
            session.emitStopped();
        } else {
            session.emit(GenerationStreamEvent.generationError(
                    safeFailureMessage(outcome),
                    Map.of(
                            "taskId", execution.taskId(),
                            "route", request.modeDecision().route(),
                            "reason", terminalReason,
                            "status", outcome.status()
                    )
            ));
        }
        session.complete();
        generationEventPublisher.publishSafely(
                request.taskRequest(),
                outcome.eventType(),
                outcome.eventMessage(),
                Map.of(
                        "taskId", execution.taskId(),
                        "route", request.modeDecision().route(),
                        "status", outcome.status(),
                        "reason", terminalReason
                )
        );
        try {
            GenerationOutcomeQuality outcomeQuality =
                    resolveOutcomeQuality(execution, null, failure);
            if (outcomeQuality.isEmpty()) {
                generationTaskLifecycleService.completeGeneration(
                        execution.taskId(), request.taskRequest().app().getId(),
                        outcome.taskStatus(), terminalReason, resultSummary);
            } else {
                generationTaskLifecycleService.completeGeneration(
                        execution.taskId(), request.taskRequest().app().getId(),
                        outcome.taskStatus(), terminalReason, resultSummary, outcomeQuality);
            }
            rememberOutcome(request, outcome.taskStatus(), resultSummary);
        } catch (RuntimeException lifecycleFailure) {
            failure.addSuppressed(lifecycleFailure);
            log.error("Failed to finalize application generation state, taskId: {}",
                    execution.taskId(), LogExceptionSanitizer.sanitize(lifecycleFailure));
        }
        generationPerformanceMonitorService.finishTask(execution.taskId(), outcome.status());
        finalizeRuntime(request, execution, session, outcome.taskStatus(), terminalReason);
    }

    /**
     * 装配 L3 结果质量证据。
     *
     * <p>只做归因采集，不参与完成判定；任一指标缺失即保持 {@code null}（语义为「未采集」），
     * 因此采集失败不会影响交付。异常路径传入 {@code failure} 以复用既有错误分类。</p>
     *
     * @param execution 任务执行上下文
     * @param outcome 流水线结果，失败路径为 {@code null}
     * @param failure 失败异常，成功路径为 {@code null}
     * @return 结果质量证据
     */
    private GenerationOutcomeQuality resolveOutcomeQuality(GenerationTaskExecution execution,
                                                          GenerationPipelineOutcome outcome,
                                                          Throwable failure) {
        Integer changedFileCount = outcome == null ? null : outcome.changedFileCount();
        Integer repairRounds = outcome == null ? null : outcome.repairRounds();
        Long firstPreviewMillis = resolveFirstPreviewMillis(execution);
        if (failure != null) {
            return GenerationOutcomeQuality.ofFailure(
                    GenerationErrorClassifier.classify(failure).category(),
                    changedFileCount, repairRounds, firstPreviewMillis);
        }
        return GenerationOutcomeQuality.ofSuccess(
                changedFileCount, repairRounds,
                resolveFirstBuildPassed(outcome, repairRounds),
                firstPreviewMillis);
    }

    /**
     * 判断是否免修复通过构建。
     *
     * <p>有修复轮次即为 {@code false}；零修复且具备构建校验证据才是 {@code true}；
     * 两者都无信息时返回 {@code null}，不臆测。</p>
     */
    private Boolean resolveFirstBuildPassed(GenerationPipelineOutcome outcome, Integer repairRounds) {
        if (repairRounds != null && repairRounds > 0) {
            return Boolean.FALSE;
        }
        if (outcome == null || outcome.completionEvidence() == null) {
            return null;
        }
        boolean buildValidated = outcome.completionEvidence()
                .contains(GenerationCompletionEvidenceType.BUILD_VALIDATION);
        if (!buildValidated) {
            return null;
        }
        return repairRounds == null ? null : Boolean.TRUE;
    }

    /** 计算提交到首个可预览版本的耗时；未就绪时返回 {@code null}。 */
    private Long resolveFirstPreviewMillis(GenerationTaskExecution execution) {
        if (execution == null || execution.session() == null
                || execution.session().executionContext() == null) {
            return null;
        }
        Instant firstPreviewReadyAt = execution.session().executionContext().firstPreviewReadyAt();
        if (firstPreviewReadyAt == null || execution.submittedAt() == null) {
            return null;
        }
        long elapsed = Duration.between(execution.submittedAt(), firstPreviewReadyAt).toMillis();
        return elapsed < 0 ? null : elapsed;
    }

    /** 处理记录结果。 */
    private void rememberOutcome(GenerationPipelineRequest request,
                                 GenerationTaskStatus status,
                                 String resultSummary) {
        if (request.taskRequest() == null
                || request.taskRequest().app() == null
                || request.taskRequest().app().getTenantId() == null
                || request.taskRequest().loginUser() == null
                || request.taskRequest().loginUser().getId() == null) {
            return;
        }
        generationOutcomeMemoryService.remember(new GenerationOutcomeMemoryRequest(
                request.requireExecution().taskId(),
                request.taskRequest().app().getTenantId(),
                request.taskRequest().app().getId(),
                request.taskRequest().loginUser().getId(),
                status,
                request.taskRequest().message(),
                resultSummary,
                request.modeDecision().route(),
                request.codeGenType() == null ? "unknown" : request.codeGenType().getValue()
        ));
    }

    private String buildFailureResultSummary(GenerationPipelineRequest request,
                                             GenerationTerminalOutcome outcome) {
        String status = switch (outcome) {
            case CANCELLED -> "已取消";
            case DEADLINE_EXCEEDED -> "已超时";
            default -> "失败";
        };
        return "任务状态：" + status
                + "\n执行路径：" + request.modeDecision().route()
                + "\n失败原因：" + safeFailureMessage(outcome);
    }

    private String terminalReason(GenerationTerminalOutcome outcome) {
        return switch (outcome) {
            case CANCELLED -> PIPELINE_CANCELLED_REASON;
            case DEADLINE_EXCEEDED -> PIPELINE_DEADLINE_REASON;
            default -> PIPELINE_FAILURE_REASON;
        };
    }

    /** 处理{@code finalize}运行时。 */
    private void finalizeRuntime(GenerationPipelineRequest request,
                                 GenerationTaskExecution execution,
                                 GenerationSession session,
                                 GenerationTaskStatus status,
                                 String reason) {
        try {
            generationTaskRuntimeLifecycleService.completeOwned(
                    execution.executionFence(), status, reason);
        } catch (RuntimeException persistenceFailure) {
            log.error("Failed to finalize durable generation task, taskId: {}",
                    execution.taskId(), LogExceptionSanitizer.sanitize(persistenceFailure));
        }
        try {
            generationSessionRegistry.retainForReplay(request.taskRequest().app().getId(), session);
        } catch (RuntimeException retentionFailure) {
            log.error("Failed to retain generation session for replay, taskId: {}",
                    execution.taskId(), LogExceptionSanitizer.sanitize(retentionFailure));
        } finally {
            // 工人可以在租约恢复已经安装更新的设备后完成
            // 同一任务的执行纪元。  切勿让老工人拆除
            // 新纪元的上下文仅通过任务 ID 来确定。
            generationExecutionContextService.finishIfOwned(
                    execution.taskId(), execution.executionFence(), status.getValue());
        }
    }

    private String safeFailureMessage(GenerationTerminalOutcome outcome) {
        return switch (outcome) {
            case DEADLINE_EXCEEDED -> "生成任务已超过最大执行时间，请稍后重试";
            case CANCELLED -> "生成任务已取消";
            default -> "生成任务执行失败，请稍后重试";
        };
    }
}
