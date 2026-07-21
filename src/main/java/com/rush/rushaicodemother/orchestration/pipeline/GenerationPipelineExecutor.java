package com.rush.rushaicodemother.orchestration.pipeline;

import com.rush.rushaicodemother.core.handler.GenerationStreamEvent;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.infrastructure.diagnostic.LogExceptionSanitizer;
import com.rush.rushaicodemother.model.enums.GenerationTaskStatus;
import com.rush.rushaicodemother.monitor.GenerationPerformanceMonitorService;
import com.rush.rushaicodemother.monitor.span.GenerationSpanCategory;
import com.rush.rushaicodemother.orchestration.GenerationSession;
import com.rush.rushaicodemother.orchestration.GenerationSessionRegistry;
import com.rush.rushaicodemother.orchestration.GenerationTerminalOutcome;
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
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Executes a submitted generation request and owns route fallback and task-runtime finalization.
 *
 * <p>Pipelines describe whether they completed synchronously, transferred completion ownership to
 * background work, or require a route fallback. This module keeps those lifecycle rules out of
 * HTTP controllers and individual route adapters.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GenerationPipelineExecutor {

    private static final int MAX_ROUTE_ATTEMPTS = 2;
    private static final String PIPELINE_FAILURE_REASON = "generation_pipeline_failed";

    private final List<GenerationPipeline> generationPipelines;
    private final GenerationEventPublisher generationEventPublisher;
    private final GenerationSessionRegistry generationSessionRegistry;
    private final GenerationExecutionContextService generationExecutionContextService;
    private final GenerationTaskRuntimeLifecycleService generationTaskRuntimeLifecycleService;
    private final GenerationPerformanceMonitorService generationPerformanceMonitorService;
    private final GenerationWorkspaceReleaseService workspaceReleaseService;
    private final GenerationTaskLifecycleService generationTaskLifecycleService;

    /** Compatibility constructor for focused tests created before publication became mandatory. */
    public GenerationPipelineExecutor(
            List<GenerationPipeline> generationPipelines,
            GenerationEventPublisher generationEventPublisher,
            GenerationSessionRegistry generationSessionRegistry,
            GenerationExecutionContextService generationExecutionContextService,
            GenerationTaskRuntimeLifecycleService generationTaskRuntimeLifecycleService,
            GenerationPerformanceMonitorService generationPerformanceMonitorService
    ) {
        this(
                generationPipelines,
                generationEventPublisher,
                generationSessionRegistry,
                generationExecutionContextService,
                generationTaskRuntimeLifecycleService,
                generationPerformanceMonitorService,
                null,
                null
        );
    }

    public void execute(GenerationPipelineRequest request) {
        GenerationTaskExecution execution = request.requireExecution();
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
                        completeManagedTask(currentRequest, outcome.terminalStatus());
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

    private GenerationPipelineRequest fallback(GenerationPipelineRequest request,
                                               GenerationPipeline failedPipeline,
                                               String pipelineReason) {
        GenerationModeDecision decision = request.modeDecision();
        String failedRoute = failedPipeline == null ? decision.route() : failedPipeline.route();
        if (decision.fallbackPolicy() != FallbackPolicy.ESCALATE_TO_HEAVY_EXPERT
                || request.workspace() == null
                || !request.workspace().exists()) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "当前生成管线不允许回退: " + failedRoute);
        }
        String reason = normalizeFallbackReason(pipelineReason, failedRoute);
        generationEventPublisher.publishSafely(
                request.taskRequest(),
                GenerationEventType.TASK_ROUTE,
                "生成管线路由已回退到重型专家模式",
                Map.of(
                        "taskId", request.requireExecution().taskId(),
                        "mode", GenerationMode.HEAVY_EXPERT.name(),
                        "route", GenerationRoute.HEAVY_GENERATION,
                        "routerReason", decision.reason(),
                        "fallbackReason", reason
                )
        );
        return request.withModeDecision(decision.withFallback(GenerationMode.HEAVY_EXPERT, reason));
    }

    private String normalizeFallbackReason(String pipelineReason, String failedRoute) {
        if (pipelineReason == null || pipelineReason.isBlank()) {
            return "pipeline_failed_or_unavailable:" + failedRoute;
        }
        return pipelineReason.trim();
    }

    private void completeManagedTask(GenerationPipelineRequest request, GenerationTaskStatus status) {
        GenerationTaskExecution execution = request.requireExecution();
        GenerationSession session = execution.session();
        if (status == GenerationTaskStatus.SUCCESS) {
            session.throwIfCancelled();
            if (workspaceReleaseService != null) {
                workspaceReleaseService.release(
                        session,
                        session.executionWorkspace() == null
                                ? request.codeGenType()
                                : session.executionWorkspace().codeGenType()
                );
            }
            if (generationTaskLifecycleService != null) {
                generationTaskLifecycleService.completeGenerationAndCharge(
                        execution.taskId(), request.taskRequest().app().getId(), status, null);
            }
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
        finalizeRuntime(request, execution, session, status, null);
    }

    private void failManagedTask(GenerationPipelineRequest request, Throwable failure) {
        GenerationTaskExecution execution = request.requireExecution();
        GenerationSession session = execution.session();
        GenerationTerminalOutcome outcome = GenerationTerminalOutcome.resolve(session, failure);
        log.error("Generation pipeline worker failed, taskId: {}, route: {}, status: {}",
                execution.taskId(), request.modeDecision().route(), outcome.status(),
                LogExceptionSanitizer.sanitize(failure));
        if (session.tryBeginCompletion()) {
            if (outcome == GenerationTerminalOutcome.CANCELLED) {
                session.emitStopped();
            } else {
                session.emit(GenerationStreamEvent.generationError(
                        safeFailureMessage(outcome),
                        Map.of(
                                "taskId", execution.taskId(),
                                "route", request.modeDecision().route(),
                                "reason", PIPELINE_FAILURE_REASON,
                                "status", outcome.status()
                        )
                ));
            }
            session.complete();
        }
        generationEventPublisher.publishSafely(
                request.taskRequest(),
                outcome.eventType(),
                outcome.eventMessage(),
                Map.of(
                        "taskId", execution.taskId(),
                        "route", request.modeDecision().route(),
                        "status", outcome.status(),
                        "reason", PIPELINE_FAILURE_REASON
                )
        );
        if (generationTaskLifecycleService != null) {
            try {
                generationTaskLifecycleService.completeGeneration(
                        execution.taskId(), request.taskRequest().app().getId(),
                        outcome.taskStatus(), PIPELINE_FAILURE_REASON);
            } catch (RuntimeException lifecycleFailure) {
                failure.addSuppressed(lifecycleFailure);
                log.error("Failed to finalize application generation state, taskId: {}",
                        execution.taskId(), LogExceptionSanitizer.sanitize(lifecycleFailure));
            }
        }
        finalizeRuntime(request, execution, session, outcome.taskStatus(), PIPELINE_FAILURE_REASON);
    }

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
            // A worker can finish after lease recovery has already installed a newer
            // execution epoch for the same task.  Never let the old worker remove the
            // newer epoch's context by task id alone.
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
