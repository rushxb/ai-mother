package com.rush.rushaicodemother.orchestration.runtime.task;

import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.model.enums.GenerationTaskStatus;
import com.rush.rushaicodemother.model.enums.TenantRole;
import com.rush.rushaicodemother.orchestration.GenerationSession;
import com.rush.rushaicodemother.orchestration.finalization.GenerationTaskFinalizer;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContextService;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.DurableGenerationTaskRecord;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.DurableGenerationTaskRepository;
import com.rush.rushaicodemother.service.tenant.TenantAuthorizationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 用于任务范围和遗留应用程序范围取消的幂等命令缝。 */
@Service
@RequiredArgsConstructor
public class GenerationTaskControlService {

    private static final String USER_REQUESTED = "user_requested";

    private final GenerationTaskQueryService generationTaskQueryService;
    private final DurableGenerationTaskRepository durableRepository;
    private final GenerationTaskRuntimeLifecycleService runtimeLifecycleService;
    private final GenerationTaskFinalizer generationTaskFinalizer;
    private final GenerationExecutionContextService executionContextService;
    private final TenantAuthorizationService tenantAuthorizationService;

    /**
 * 取消生成任务{@code Control}。
 *
 * @param taskId 任务编号
 * @param actor 操作发起人
 * @return 满足条件时返回 {@code true}，否则返回 {@code false}
 */
    public GenerationTaskSnapshot cancel(String taskId, User actor) {
        requireActor(actor);
        DurableGenerationTaskRecord task = durableRepository.findByTaskId(taskId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.NOT_FOUND_ERROR, "Generation task does not exist"));
        requireDeveloper(task, actor);
        GenerationTaskSnapshot snapshot = generationTaskQueryService.get(taskId, actor);
        if (isTerminal(snapshot.status())) {
            return snapshot;
        }
        cancelLocalAndDurable(taskId);
        return generationTaskQueryService.get(taskId, actor);
    }

    /**
 * 取消活动{@code For}应用。
 *
 * @param appId 应用编号
 * @param actor 操作发起人
 * @return 满足条件时返回 {@code true}，否则返回 {@code false}
 */
    public GenerationTaskSnapshot cancelActiveForApp(Long appId, User actor) {
        if (appId == null || appId <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "应用 ID 不合法");
        }
        requireActor(actor);
        DurableGenerationTaskRecord task = durableRepository.findLatestNonTerminalByAppId(appId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.OPERATION_ERROR, "当前应用没有正在运行的生成任务"));
        requireDeveloper(task, actor);
        cancelLocalAndDurable(task.taskId());
        return generationTaskQueryService.get(task.taskId(), actor);
    }

    /** 取消{@code Local}{@code And}持久。 */
    private void cancelLocalAndDurable(String taskId) {
        runtimeLifecycleService.requestCancellation(taskId, USER_REQUESTED);
        GenerationSession session = generationTaskQueryService.localSession(taskId);
        if (session != null && session.isActive()) {
            session.cancel();
        }
        executionContextService.cancelByTaskId(taskId, USER_REQUESTED);
        DurableGenerationTaskRecord durableTask = durableRepository.findByTaskId(taskId).orElse(null);
        if (durableTask != null && durableTask.status() == GenerationTaskStatus.WAITING_APPROVAL) {
            generationTaskFinalizer.finalizeUnownedRuntime(
                    taskId, GenerationTaskStatus.CANCELLED, USER_REQUESTED);
        }
    }

    private boolean isTerminal(String status) {
        GenerationTaskStatus taskStatus = GenerationTaskStatus.fromValue(status);
        return taskStatus != null && taskStatus.isTerminal();
    }

    private void requireDeveloper(DurableGenerationTaskRecord task, User actor) {
        tenantAuthorizationService.requireRole(
                task.tenantId(), actor.getId(), TenantRole.DEVELOPER,
                "No permission to control this generation task");
    }

    private void requireActor(User actor) {
        if (actor == null || actor.getId() == null || actor.getId() <= 0) {
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR, "User is not logged in");
        }
    }
}
