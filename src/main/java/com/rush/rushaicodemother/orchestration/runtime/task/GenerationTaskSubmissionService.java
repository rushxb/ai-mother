package com.rush.rushaicodemother.orchestration.runtime.task;

import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.model.enums.GenerationTaskStatus;
import com.rush.rushaicodemother.orchestration.GenerationSession;
import com.rush.rushaicodemother.orchestration.GenerationSessionRegistry;
import com.rush.rushaicodemother.orchestration.GenerationTaskResult;
import com.rush.rushaicodemother.orchestration.pipeline.GenerationPipelineExecutor;
import com.rush.rushaicodemother.orchestration.pipeline.GenerationPipelineRequest;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContext;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContextService;
import com.rush.rushaicodemother.orchestration.runtime.identity.GenerationTaskIdGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;


/**
 * Creates the task execution envelope and submits all generation routes through one worker seam.
 */
@Service
@RequiredArgsConstructor
public class GenerationTaskSubmissionService {

    private final GenerationTaskIdGenerator generationTaskIdGenerator;
    private final GenerationExecutionContextService generationExecutionContextService;
    private final GenerationSessionRegistry generationSessionRegistry;
    private final GenerationTaskExecutor generationTaskExecutor;
    private final GenerationPipelineExecutor generationPipelineExecutor;
    private final GenerationTaskRuntimeLifecycleService generationTaskRuntimeLifecycleService;

    public GenerationTaskResult submit(GenerationPipelineRequest request) {
        if (request == null || request.taskRequest() == null) {
            throw new IllegalArgumentException("generation pipeline request cannot be null");
        }
        App app = request.taskRequest().app();
        User user = request.taskRequest().loginUser();
        if (app == null || app.getId() == null || user == null || user.getId() == null) {
            throw new IllegalArgumentException("generation task identity is incomplete");
        }

        synchronized (generationSessionRegistry.lock(app.getId())) {
            removeCompletedSession(app.getId());
            generationSessionRegistry.assertNoActiveSession(app.getId());

            String taskId = generationTaskIdGenerator.nextId();
            GenerationExecutionContext executionContext = null;
            GenerationSession session = null;
            boolean durableSubmitted = false;
            try {
                executionContext = generationExecutionContextService.start(taskId, app.getId(), user.getId());
                session = new GenerationSession(null, executionContext);
                session.bindTaskRequest(request.taskRequest());
                session.recordRoute(request.modeDecision().route());

                GenerationTaskExecution execution = new GenerationTaskExecution(
                        taskId, session, executionContext, executionContext.startedAt());
                generationTaskRuntimeLifecycleService.submit(execution, request.modeDecision().route());
                durableSubmitted = true;
                generationSessionRegistry.put(app.getId(), session);

                GenerationPipelineRequest executableRequest = request.withExecution(execution);
                generationTaskExecutor.execute(taskId, () -> generationPipelineExecutor.execute(executableRequest));

                return new GenerationTaskResult(
                        taskId,
                        request.modeDecision().route(),
                        request.workspace(),
                        session.asFlux()
                );
            } catch (RuntimeException submissionFailure) {
                if (durableSubmitted) {
                    try {
                        generationTaskRuntimeLifecycleService.complete(
                                taskId, GenerationTaskStatus.FAILED,
                                "submission_failed");
                    } catch (RuntimeException compensationFailure) {
                        submissionFailure.addSuppressed(compensationFailure);
                    }
                }
                if (session != null) {
                    generationSessionRegistry.remove(app.getId(), session);
                    session.complete();
                }
                if (executionContext != null) {
                    generationExecutionContextService.finish(taskId, "submission_failed");
                }
                throw submissionFailure;
            }
        }
    }

    private void removeCompletedSession(Long appId) {
        GenerationSession existing = generationSessionRegistry.get(appId);
        if (existing != null && !existing.isActive()) {
            generationSessionRegistry.remove(appId, existing);
        }
    }
}
