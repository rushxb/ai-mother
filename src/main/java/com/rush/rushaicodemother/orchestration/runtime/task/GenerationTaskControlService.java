package com.rush.rushaicodemother.orchestration.runtime.task;

import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.model.enums.GenerationTaskStatus;
import com.rush.rushaicodemother.orchestration.GenerationSession;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContextService;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.DurableGenerationTaskRecord;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.DurableGenerationTaskRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Objects;

/** Idempotent command seam for task-scoped and legacy app-scoped cancellation. */
@Service
@RequiredArgsConstructor
public class GenerationTaskControlService {

    private static final String USER_REQUESTED = "user_requested";

    private final GenerationTaskQueryService generationTaskQueryService;
    private final DurableGenerationTaskRepository durableRepository;
    private final GenerationTaskRuntimeLifecycleService runtimeLifecycleService;
    private final GenerationExecutionContextService executionContextService;

    public GenerationTaskSnapshot cancel(String taskId, User actor) {
        GenerationTaskSnapshot snapshot = generationTaskQueryService.get(taskId, actor);
        if (isTerminal(snapshot.status())) {
            return snapshot;
        }
        cancelLocalAndDurable(taskId);
        return generationTaskQueryService.get(taskId, actor);
    }

    public GenerationTaskSnapshot cancelActiveForApp(Long appId, User actor) {
        if (appId == null || appId <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "应用 ID 不合法");
        }
        if (actor == null || actor.getId() == null) {
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR, "用户未登录");
        }
        DurableGenerationTaskRecord task = durableRepository.findLatestNonTerminalByAppId(appId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.OPERATION_ERROR, "当前应用没有正在运行的生成任务"));
        if (!Objects.equals(task.userId(), actor.getId())) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "无权操作该生成任务");
        }
        cancelLocalAndDurable(task.taskId());
        return generationTaskQueryService.get(task.taskId(), actor);
    }

    private void cancelLocalAndDurable(String taskId) {
        runtimeLifecycleService.requestCancellation(taskId, USER_REQUESTED);
        GenerationSession session = generationTaskQueryService.localSession(taskId);
        if (session != null && session.isActive()) {
            session.cancel();
        }
        executionContextService.cancelByTaskId(taskId, USER_REQUESTED);
    }

    private boolean isTerminal(String status) {
        GenerationTaskStatus taskStatus = GenerationTaskStatus.fromValue(status);
        return taskStatus != null && taskStatus.isTerminal();
    }
}
