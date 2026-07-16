package com.rush.rushaicodemother.orchestration.runtime.task;

import com.rush.rushaicodemother.core.handler.GenerationStreamEvent;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.infrastructure.diagnostic.LogExceptionSanitizer;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.orchestration.GenerationSession;
import com.rush.rushaicodemother.orchestration.GenerationSessionRegistry;
import com.rush.rushaicodemother.orchestration.GenerationTaskRequest;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContext;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionSnapshot;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.DurableGenerationTaskRecord;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.DurableGenerationTaskRepository;
import com.rush.rushaicodemother.orchestration.runtime.task.progress.GenerationTaskProgressEstimate;
import com.rush.rushaicodemother.orchestration.runtime.task.progress.GenerationTaskProgressEstimator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/** Read-only task query seam with local realtime data, durable fallback and telemetry-derived ETA. */
@Slf4j
@Service
@RequiredArgsConstructor
public class GenerationTaskQueryService {

    private static final String RUNNING_STATUS = "running";
    private static final String CANCELLING_STATUS = "cancelling";

    private final GenerationSessionRegistry generationSessionRegistry;
    private final DurableGenerationTaskRepository durableRepository;
    private final GenerationTaskProgressEstimator progressEstimator;

    public GenerationTaskSnapshot get(String taskId, User actor) {
        requireActor(actor);
        GenerationSession session = generationSessionRegistry.getByTaskId(taskId);
        if (session != null) {
            assertOwnedSession(session, actor);
            return localSnapshot(session, safeFindDurableMetadata(taskId));
        }
        DurableGenerationTaskRecord task = requireDurableTask(taskId);
        assertOwnedTask(task, actor);
        return durableSnapshot(task);
    }

    public Flux<GenerationStreamEvent> events(String taskId, User actor) {
        requireActor(actor);
        GenerationSession session = generationSessionRegistry.getByTaskId(taskId);
        if (session != null) {
            assertOwnedSession(session, actor);
            return session.asFlux();
        }
        DurableGenerationTaskRecord task = requireDurableTask(taskId);
        assertOwnedTask(task, actor);
        throw new BusinessException(
                ErrorCode.OPERATION_ERROR,
                "生成任务实时事件流仅在执行实例和回放保留期内可用"
        );
    }

    GenerationSession localSession(String taskId) {
        return generationSessionRegistry.getByTaskId(taskId);
    }

    DurableGenerationTaskRecord requireDurableTask(String taskId) {
        return durableRepository.findByTaskId(taskId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND_ERROR, "生成任务不存在"));
    }

    private GenerationTaskSnapshot localSnapshot(GenerationSession session,
                                                   DurableGenerationTaskRecord durableMetadata) {
        GenerationExecutionContext executionContext = session.executionContext();
        if (executionContext == null) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "生成任务执行上下文不存在");
        }
        GenerationExecutionSnapshot execution = executionContext.snapshot();
        String status = execution.terminalStatus() != null
                ? execution.terminalStatus()
                : execution.cancelled() ? CANCELLING_STATUS : RUNNING_STATUS;
        String route = session.route() != null
                ? session.route()
                : durableMetadata == null ? null : durableMetadata.route();
        Instant submittedAt = durableMetadata == null
                ? execution.startedAt()
                : durableMetadata.submittedAt();
        Instant deadlineAt = durableMetadata != null && durableMetadata.deadlineAt() != null
                ? durableMetadata.deadlineAt()
                : execution.deadlineAt();
        String stage = durableMetadata == null ? null : durableMetadata.stage();
        String stageMessage = durableMetadata == null ? null : durableMetadata.stageMessage();
        GenerationTaskProgressEstimate progress = progressEstimator.estimate(
                route, status, submittedAt, deadlineAt, stage);
        return new GenerationTaskSnapshot(
                execution.taskId(), execution.appId(), execution.userId(), route, status,
                stage, stageMessage, submittedAt, deadlineAt, execution.cancelled(),
                execution.cancellationReason(), execution.usages(), execution.limits(), progress
        );
    }

    private GenerationTaskSnapshot durableSnapshot(DurableGenerationTaskRecord task) {
        String status = task.status().getValue();
        GenerationTaskProgressEstimate progress = progressEstimator.estimate(
                task.route(), status, task.submittedAt(), task.deadlineAt(), task.stage());
        return new GenerationTaskSnapshot(
                task.taskId(), task.appId(), task.userId(), task.route(), status,
                task.stage(), task.stageMessage(), task.submittedAt(), task.deadlineAt(),
                task.cancellationRequested(), task.cancellationReason(), Map.of(), Map.of(), progress
        );
    }

    private DurableGenerationTaskRecord safeFindDurableMetadata(String taskId) {
        try {
            return durableRepository.findByTaskId(taskId).orElse(null);
        } catch (RuntimeException failure) {
            log.warn("Durable task metadata unavailable for local task status, taskId: {}, error: {}",
                    taskId, LogExceptionSanitizer.sanitize(failure).getMessage());
            return null;
        }
    }

    private void assertOwnedSession(GenerationSession session, User actor) {
        GenerationTaskRequest taskRequest = session.taskRequest();
        if (taskRequest == null || taskRequest.loginUser() == null
                || !Objects.equals(taskRequest.loginUser().getId(), actor.getId())) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "无权访问该生成任务");
        }
    }

    private void assertOwnedTask(DurableGenerationTaskRecord task, User actor) {
        if (!Objects.equals(task.userId(), actor.getId())) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "无权访问该生成任务");
        }
    }

    private void requireActor(User actor) {
        if (actor == null || actor.getId() == null) {
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR, "用户未登录");
        }
    }
}
