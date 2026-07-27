package com.rush.rushaicodemother.orchestration.runtime.task;

import com.rush.rushaicodemother.model.enums.GenerationTaskStatus;
import com.rush.rushaicodemother.orchestration.GenerationTaskResult;
import com.rush.rushaicodemother.orchestration.event.GenerationEventPublisher;
import com.rush.rushaicodemother.orchestration.pipeline.GenerationPipelineRequest;
import com.rush.rushaicodemother.orchestration.eventstream.GenerationEventStream;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationSlaEnvelope;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationSlaPolicy;
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
    private final GenerationSlaPolicy generationSlaPolicy;
    private final GenerationTaskDispatcher taskDispatcher;
    private final GenerationTaskAdmissionService taskAdmissionService;
    private final GenerationTaskRuntimeLifecycleService generationTaskRuntimeLifecycleService;
    private final GenerationEventStream generationEventStream;
    private final GenerationEventPublisher generationEventPublisher;
    private final GenerationTraceContextBridge traceContextBridge;
    private final Clock clock;

    @Autowired
    public GenerationTaskSubmissionService(GenerationTaskIdGenerator generationTaskIdGenerator,
                                           GenerationSlaPolicy generationSlaPolicy,
                                           GenerationTaskDispatcher taskDispatcher,
                                           GenerationTaskAdmissionService taskAdmissionService,
                                           GenerationTaskRuntimeLifecycleService generationTaskRuntimeLifecycleService,
                                           GenerationEventStream generationEventStream,
                                           GenerationEventPublisher generationEventPublisher,
                                           GenerationTraceContextBridge traceContextBridge) {
        this(generationTaskIdGenerator, generationSlaPolicy, taskDispatcher, taskAdmissionService,
                generationTaskRuntimeLifecycleService, generationEventStream, generationEventPublisher,
                traceContextBridge, Clock.systemUTC());
    }

    GenerationTaskSubmissionService(GenerationTaskIdGenerator generationTaskIdGenerator,
                                    GenerationSlaPolicy generationSlaPolicy,
                                    GenerationTaskDispatcher taskDispatcher,
                                    GenerationTaskAdmissionService taskAdmissionService,
                                    GenerationTaskRuntimeLifecycleService generationTaskRuntimeLifecycleService,
                                    GenerationEventStream generationEventStream,
                                    Clock clock) {
        this(generationTaskIdGenerator, generationSlaPolicy, taskDispatcher, taskAdmissionService,
                generationTaskRuntimeLifecycleService, generationEventStream, null,
                GenerationTraceContextBridge.NOOP, clock);
    }

    GenerationTaskSubmissionService(GenerationTaskIdGenerator generationTaskIdGenerator,
                                    GenerationSlaPolicy generationSlaPolicy,
                                    GenerationTaskDispatcher taskDispatcher,
                                    GenerationTaskAdmissionService taskAdmissionService,
                                     GenerationTaskRuntimeLifecycleService generationTaskRuntimeLifecycleService,
                                     GenerationEventStream generationEventStream,
                                     GenerationTraceContextBridge traceContextBridge,
                                     Clock clock) {
        this(generationTaskIdGenerator, generationSlaPolicy, taskDispatcher, taskAdmissionService,
                generationTaskRuntimeLifecycleService, generationEventStream, null,
                traceContextBridge, clock);
    }

    GenerationTaskSubmissionService(GenerationTaskIdGenerator generationTaskIdGenerator,
                                    GenerationSlaPolicy generationSlaPolicy,
                                    GenerationTaskDispatcher taskDispatcher,
                                    GenerationTaskAdmissionService taskAdmissionService,
                                    GenerationTaskRuntimeLifecycleService generationTaskRuntimeLifecycleService,
                                    GenerationEventStream generationEventStream,
                                    GenerationEventPublisher generationEventPublisher,
                                    GenerationTraceContextBridge traceContextBridge,
                                    Clock clock) {
        this.generationTaskIdGenerator = generationTaskIdGenerator;
        this.generationSlaPolicy = generationSlaPolicy;
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

    public GenerationTaskResult submit(GenerationPipelineRequest request) {
        return submit(request, GenerationTaskIdempotency.none());
    }

    public GenerationTaskResult submit(GenerationPipelineRequest request,
                                       GenerationTaskIdempotency idempotency) {
        if (request == null || request.taskRequest() == null) {
            throw new IllegalArgumentException("generation pipeline request cannot be null");
        }
        if (idempotency == null) {
            throw new IllegalArgumentException("generation task idempotency cannot be null");
        }
        if (request.taskRequest().app() == null || request.taskRequest().app().getId() == null
                || request.taskRequest().app().getTenantId() == null
                || request.taskRequest().app().getTenantId() <= 0
                || request.taskRequest().loginUser() == null
                || request.taskRequest().loginUser().getId() == null) {
            throw new IllegalArgumentException("generation task identity is incomplete");
        }
        String taskId = generationTaskIdGenerator.nextId();
        Instant submittedAt = clock.instant();
        GenerationSlaEnvelope slaEnvelope = generationSlaPolicy.resolve(
                request.modeDecision(), request.codeGenType());
        GenerationTaskCommand command = GenerationTaskCommand.from(
                taskId,
                request,
                submittedAt,
                slaEnvelope,
                traceContextBridge.capture()
        );
        GenerationTaskAdmissionResult admission = null;
        try {
            admission = taskAdmissionService.admit(command, idempotency);
            if (admission.created()) {
                if (generationEventPublisher != null) {
                    generationEventPublisher.clearRecent(command.appId());
                }
                taskDispatcher.dispatch(admission.taskId());
            }
            return new GenerationTaskResult(
                    admission.taskId(),
                    admission.route(),
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
