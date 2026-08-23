package com.rush.rushaicodemother.orchestration.runtime.task;

import com.rush.rushaicodemother.model.enums.GenerationTaskStatus;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.GenerationTaskRequest;
import com.rush.rushaicodemother.orchestration.GenerationTaskResult;
import com.rush.rushaicodemother.orchestration.decision.GenerationPreflightUsage;
import com.rush.rushaicodemother.orchestration.decision.GenerationScenarioPreflight;
import com.rush.rushaicodemother.orchestration.decision.GenerationScenarioPreflightResult;
import com.rush.rushaicodemother.orchestration.event.GenerationEventPublisher;
import com.rush.rushaicodemother.orchestration.finalization.GenerationTaskFinalizer;
import com.rush.rushaicodemother.orchestration.pipeline.GenerationPipelineRequest;
import com.rush.rushaicodemother.orchestration.eventstream.GenerationEventStream;
import com.rush.rushaicodemother.orchestration.plan.GenerationExecutionPlan;
import com.rush.rushaicodemother.orchestration.plan.GenerationExecutionPlanner;
import com.rush.rushaicodemother.orchestration.runtime.identity.GenerationTaskIdGenerator;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.GenerationTaskCommand;
import com.rush.rushaicodemother.orchestration.runtime.tracing.GenerationTraceContextBridge;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import com.rush.rushaicodemother.infrastructure.diagnostic.LogExceptionSanitizer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

/**
 * 创建任务执行信封并通过一个worker seam提交所有生成路线。
 */
@Service
@Slf4j
public class GenerationTaskSubmissionService {

    private final GenerationTaskIdGenerator generationTaskIdGenerator;
    private final GenerationScenarioPreflight scenarioPreflight;
    private final GenerationExecutionPlanner generationExecutionPlanner;
    private final GenerationTaskDispatcher taskDispatcher;
    private final GenerationTaskAdmissionService taskAdmissionService;
    private final GenerationTaskFinalizer generationTaskFinalizer;
    private final GenerationEventStream generationEventStream;
    private final GenerationEventPublisher generationEventPublisher;
    private final GenerationTraceContextBridge traceContextBridge;
    private final Clock clock;

    @Autowired
    public GenerationTaskSubmissionService(GenerationTaskIdGenerator generationTaskIdGenerator,
                                           GenerationScenarioPreflight scenarioPreflight,
                                           GenerationExecutionPlanner generationExecutionPlanner,
                                           GenerationTaskDispatcher taskDispatcher,
                                           GenerationTaskAdmissionService taskAdmissionService,
                                           GenerationTaskFinalizer generationTaskFinalizer,
                                           GenerationEventStream generationEventStream,
                                           GenerationEventPublisher generationEventPublisher,
                                           GenerationTraceContextBridge traceContextBridge) {
        this(generationTaskIdGenerator, scenarioPreflight, generationExecutionPlanner,
                taskDispatcher, taskAdmissionService,
                generationTaskFinalizer, generationEventStream, generationEventPublisher,
                traceContextBridge, Clock.systemUTC());
    }

    GenerationTaskSubmissionService(GenerationTaskIdGenerator generationTaskIdGenerator,
                                    GenerationExecutionPlanner generationExecutionPlanner,
                                    GenerationTaskDispatcher taskDispatcher,
                                    GenerationTaskAdmissionService taskAdmissionService,
                                    GenerationTaskFinalizer generationTaskFinalizer,
                                    GenerationEventStream generationEventStream,
                                    Clock clock) {
        this(generationTaskIdGenerator, generationExecutionPlanner, taskDispatcher, taskAdmissionService,
                generationTaskFinalizer, generationEventStream, null,
                GenerationTraceContextBridge.NOOP, clock);
    }

    GenerationTaskSubmissionService(GenerationTaskIdGenerator generationTaskIdGenerator,
                                    GenerationExecutionPlanner generationExecutionPlanner,
                                    GenerationTaskDispatcher taskDispatcher,
                                    GenerationTaskAdmissionService taskAdmissionService,
                                     GenerationTaskFinalizer generationTaskFinalizer,
                                     GenerationEventStream generationEventStream,
                                     GenerationTraceContextBridge traceContextBridge,
                                     Clock clock) {
        this(generationTaskIdGenerator, generationExecutionPlanner, taskDispatcher, taskAdmissionService,
                generationTaskFinalizer, generationEventStream, null,
                traceContextBridge, clock);
    }

    /** 创建生成任务提交服务实例并完成必要的依赖和初始状态设置。 */
    GenerationTaskSubmissionService(GenerationTaskIdGenerator generationTaskIdGenerator,
                                    GenerationExecutionPlanner generationExecutionPlanner,
                                    GenerationTaskDispatcher taskDispatcher,
                                    GenerationTaskAdmissionService taskAdmissionService,
                                    GenerationTaskFinalizer generationTaskFinalizer,
                                    GenerationEventStream generationEventStream,
                                    GenerationEventPublisher generationEventPublisher,
                                     GenerationTraceContextBridge traceContextBridge,
                                     Clock clock) {
        this(generationTaskIdGenerator, null, generationExecutionPlanner, taskDispatcher,
                taskAdmissionService, generationTaskFinalizer, generationEventStream,
                generationEventPublisher, traceContextBridge, clock);
    }

    GenerationTaskSubmissionService(GenerationTaskIdGenerator generationTaskIdGenerator,
                                    GenerationScenarioPreflight scenarioPreflight,
                                    GenerationExecutionPlanner generationExecutionPlanner,
                                    GenerationTaskDispatcher taskDispatcher,
                                    GenerationTaskAdmissionService taskAdmissionService,
                                    GenerationTaskFinalizer generationTaskFinalizer,
                                    GenerationEventStream generationEventStream,
                                    GenerationEventPublisher generationEventPublisher,
                                    GenerationTraceContextBridge traceContextBridge,
                                    Clock clock) {
        this.generationTaskIdGenerator = generationTaskIdGenerator;
        this.scenarioPreflight = scenarioPreflight;
        this.generationExecutionPlanner = generationExecutionPlanner;
        this.taskDispatcher = taskDispatcher;
        this.taskAdmissionService = taskAdmissionService;
        this.generationTaskFinalizer = generationTaskFinalizer;
        this.generationEventStream = generationEventStream;
        this.generationEventPublisher = generationEventPublisher;
        this.traceContextBridge = traceContextBridge == null
                ? GenerationTraceContextBridge.NOOP
                : traceContextBridge;
        this.clock = clock;
    }

    /**
 * 校验并提交当前请求。
 *
 * @param request 请求参数
 * @return 方法执行结果
 */
    public GenerationTaskResult submit(GenerationPipelineRequest request) {
        return submit(request, GenerationTaskIdempotency.none());
    }

    /**
 * 校验并提交当前请求。
 *
 * @param request 请求参数
 * @param idempotency {@code idempotency} 对应的调用参数
 * @return 方法执行结果
 */
    public GenerationTaskResult submit(GenerationPipelineRequest request,
                                       GenerationTaskIdempotency idempotency) {
        return submitPrepared(
                generationTaskIdGenerator.nextId(),
                clock.instant(),
                request,
                GenerationPreflightUsage.none(),
                false,
                idempotency);
    }

    /** 主提交入口：先创建 task identity，再执行场景 preflight 与最终计划冻结。 */
    public GenerationTaskResult submit(GenerationTaskRequest taskRequest,
                                       CodeGenTypeEnum codeGenType,
                                       GenerationWorkspace workspace,
                                       GenerationTaskIdempotency idempotency) {
        if (scenarioPreflight == null) {
            throw new IllegalStateException("场景 preflight 模块未配置");
        }
        if (idempotency == null) {
            throw new IllegalArgumentException("生成任务幂等信息不能为空");
        }
        Optional<GenerationTaskSubmissionReceipt> replay =
                taskAdmissionService.findIdempotentReplay(taskRequest, idempotency);
        if (replay != null && replay.isPresent()) {
            GenerationTaskSubmissionReceipt receipt = replay.get();
            return new GenerationTaskResult(
                    receipt,
                    workspace,
                    generationEventStream.stream(receipt.taskId()),
                    false);
        }
        String taskId = generationTaskIdGenerator.nextId();
        Instant submittedAt = clock.instant();
        GenerationScenarioPreflightResult preflight = scenarioPreflight.prepare(
                taskId, submittedAt, taskRequest, codeGenType, workspace);
        CodeGenTypeEnum resolvedTargetType = preflight.scenarioDecision().targetType();
        GenerationPipelineRequest request = new GenerationPipelineRequest(
                taskRequest, resolvedTargetType, workspace, preflight.scenarioDecision());
        return submitPrepared(
                taskId, submittedAt, request, preflight.usage(), preflight.creditReserved(), idempotency);
    }

    private GenerationTaskResult submitPrepared(String taskId,
                                                Instant submittedAt,
                                                GenerationPipelineRequest request,
                                                GenerationPreflightUsage preflightUsage,
                                                boolean preflightCreditReserved,
                                                GenerationTaskIdempotency idempotency) {
        // 先处理前置条件和快速返回分支，避免无效输入进入核心流程。
        if (request == null || request.taskRequest() == null) {
            throw new IllegalArgumentException("生成流水线请求不能为空");
        }
        if (idempotency == null) {
            throw new IllegalArgumentException("生成任务幂等信息不能为空");
        }
        if (request.taskRequest().app() == null || request.taskRequest().app().getId() == null
                || request.taskRequest().app().getTenantId() == null
                || request.taskRequest().app().getTenantId() <= 0
                || request.taskRequest().loginUser() == null
                || request.taskRequest().loginUser().getId() == null) {
            throw new IllegalArgumentException("生成任务身份信息不完整");
        }
        GenerationTaskAdmissionResult admission = null;
        // 将可能失败的操作收敛在统一异常边界内，便于清理资源和转换错误。
        try {
            GenerationExecutionPlan executionPlan = generationExecutionPlanner.plan(request, preflightUsage);
            GenerationPipelineRequest plannedRequest = request.withExecutionPlan(executionPlan);
            GenerationTaskCommand command = GenerationTaskCommand.from(
                    taskId,
                    plannedRequest,
                    submittedAt,
                    executionPlan.sla(),
                    traceContextBridge.capture(),
                    preflightUsage
            );
            admission = taskAdmissionService.admit(command, idempotency);
            if (admission.created()) {
                if (generationEventPublisher != null) {
                    generationEventPublisher.clearRecent(command.appId());
                }
                taskDispatcher.dispatch(admission.taskId());
            } else if (preflightCreditReserved) {
                settleReplayedPreflightBestEffort(taskId);
            }
            return new GenerationTaskResult(
                    admission.submission(),
                    request.workspace(),
                    generationEventStream.stream(admission.taskId()),
                    admission.created()
            );
        } catch (RuntimeException submissionFailure) {
            if (admission != null && admission.created()) {
                try {
                    generationTaskFinalizer.finalizeUnownedRuntime(
                            admission.taskId(), GenerationTaskStatus.FAILED, "submission_failed");
                } catch (RuntimeException compensationFailure) {
                    submissionFailure.addSuppressed(compensationFailure);
                }
            } else if (preflightCreditReserved) {
                try {
                    taskAdmissionService.settlePreflightReservation(taskId);
                } catch (RuntimeException compensationFailure) {
                    submissionFailure.addSuppressed(compensationFailure);
                }
            }
            throw submissionFailure;
        }
    }

    /** 并发幂等复用成功时，原请求继续成功返回，孤儿预授权交给同步尝试和后台恢复兜底。 */
    private void settleReplayedPreflightBestEffort(String taskId) {
        try {
            taskAdmissionService.settlePreflightReservation(taskId);
        } catch (RuntimeException settlementFailure) {
            log.warn("并发幂等复用后的模型预检额度暂未结算，taskId: {}, error: {}",
                    taskId, LogExceptionSanitizer.sanitize(settlementFailure));
        }
    }
}
