package com.rush.rushaicodemother.orchestration.runtime.task;

import com.rush.rushaicodemother.model.enums.GenerationTaskStatus;
import com.rush.rushaicodemother.orchestration.GenerationTaskResult;
import com.rush.rushaicodemother.orchestration.event.GenerationEventPublisher;
import com.rush.rushaicodemother.orchestration.pipeline.GenerationPipelineRequest;
import com.rush.rushaicodemother.orchestration.eventstream.GenerationEventStream;
import com.rush.rushaicodemother.orchestration.plan.GenerationExecutionPlan;
import com.rush.rushaicodemother.orchestration.plan.GenerationExecutionPlanner;
import com.rush.rushaicodemother.orchestration.runtime.identity.GenerationTaskIdGenerator;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.GenerationTaskCommand;
import com.rush.rushaicodemother.orchestration.runtime.tracing.GenerationTraceContextBridge;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;

/**
 * 创建任务执行信封并通过一个worker seam提交所有生成路线。
 */
@Service
public class GenerationTaskSubmissionService {

    private final GenerationTaskIdGenerator generationTaskIdGenerator;
    private final GenerationExecutionPlanner generationExecutionPlanner;
    private final GenerationTaskDispatcher taskDispatcher;
    private final GenerationTaskAdmissionService taskAdmissionService;
    private final GenerationTaskRuntimeLifecycleService generationTaskRuntimeLifecycleService;
    private final GenerationEventStream generationEventStream;
    private final GenerationEventPublisher generationEventPublisher;
    private final GenerationTraceContextBridge traceContextBridge;
    private final Clock clock;

    @Autowired
    public GenerationTaskSubmissionService(GenerationTaskIdGenerator generationTaskIdGenerator,
                                           GenerationExecutionPlanner generationExecutionPlanner,
                                           GenerationTaskDispatcher taskDispatcher,
                                           GenerationTaskAdmissionService taskAdmissionService,
                                           GenerationTaskRuntimeLifecycleService generationTaskRuntimeLifecycleService,
                                           GenerationEventStream generationEventStream,
                                           GenerationEventPublisher generationEventPublisher,
                                           GenerationTraceContextBridge traceContextBridge) {
        this(generationTaskIdGenerator, generationExecutionPlanner, taskDispatcher, taskAdmissionService,
                generationTaskRuntimeLifecycleService, generationEventStream, generationEventPublisher,
                traceContextBridge, Clock.systemUTC());
    }

    GenerationTaskSubmissionService(GenerationTaskIdGenerator generationTaskIdGenerator,
                                    GenerationExecutionPlanner generationExecutionPlanner,
                                    GenerationTaskDispatcher taskDispatcher,
                                    GenerationTaskAdmissionService taskAdmissionService,
                                    GenerationTaskRuntimeLifecycleService generationTaskRuntimeLifecycleService,
                                    GenerationEventStream generationEventStream,
                                    Clock clock) {
        this(generationTaskIdGenerator, generationExecutionPlanner, taskDispatcher, taskAdmissionService,
                generationTaskRuntimeLifecycleService, generationEventStream, null,
                GenerationTraceContextBridge.NOOP, clock);
    }

    GenerationTaskSubmissionService(GenerationTaskIdGenerator generationTaskIdGenerator,
                                    GenerationExecutionPlanner generationExecutionPlanner,
                                    GenerationTaskDispatcher taskDispatcher,
                                    GenerationTaskAdmissionService taskAdmissionService,
                                     GenerationTaskRuntimeLifecycleService generationTaskRuntimeLifecycleService,
                                     GenerationEventStream generationEventStream,
                                     GenerationTraceContextBridge traceContextBridge,
                                     Clock clock) {
        this(generationTaskIdGenerator, generationExecutionPlanner, taskDispatcher, taskAdmissionService,
                generationTaskRuntimeLifecycleService, generationEventStream, null,
                traceContextBridge, clock);
    }

    /** 创建生成任务提交服务实例并完成必要的依赖和初始状态设置。 */
    GenerationTaskSubmissionService(GenerationTaskIdGenerator generationTaskIdGenerator,
                                    GenerationExecutionPlanner generationExecutionPlanner,
                                    GenerationTaskDispatcher taskDispatcher,
                                    GenerationTaskAdmissionService taskAdmissionService,
                                    GenerationTaskRuntimeLifecycleService generationTaskRuntimeLifecycleService,
                                    GenerationEventStream generationEventStream,
                                    GenerationEventPublisher generationEventPublisher,
                                    GenerationTraceContextBridge traceContextBridge,
                                    Clock clock) {
        this.generationTaskIdGenerator = generationTaskIdGenerator;
        this.generationExecutionPlanner = generationExecutionPlanner;
        this.taskDispatcher = taskDispatcher;
        this.taskAdmissionService = taskAdmissionService;
        this.generationTaskRuntimeLifecycleService = generationTaskRuntimeLifecycleService;
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
        String taskId = generationTaskIdGenerator.nextId();
        Instant submittedAt = clock.instant();
        GenerationExecutionPlan executionPlan = generationExecutionPlanner.plan(request);
        GenerationPipelineRequest plannedRequest = request.withExecutionPlan(executionPlan);
        GenerationTaskCommand command = GenerationTaskCommand.from(
                taskId,
                plannedRequest,
                submittedAt,
                executionPlan.sla(),
                traceContextBridge.capture()
        );
        GenerationTaskAdmissionResult admission = null;
        // 将可能失败的操作收敛在统一异常边界内，便于清理资源和转换错误。
        try {
            admission = taskAdmissionService.admit(command, idempotency);
            if (admission.created()) {
                if (generationEventPublisher != null) {
                    generationEventPublisher.clearRecent(command.appId());
                }
                taskDispatcher.dispatch(admission.taskId());
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
                    generationTaskRuntimeLifecycleService.completeUnowned(
                            admission.taskId(), GenerationTaskStatus.FAILED, "submission_failed");
                } catch (RuntimeException compensationFailure) {
                    submissionFailure.addSuppressed(compensationFailure);
                }
            }
            throw submissionFailure;
        }
    }
}
