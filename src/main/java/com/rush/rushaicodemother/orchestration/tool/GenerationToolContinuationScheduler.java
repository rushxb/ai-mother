package com.rush.rushaicodemother.orchestration.tool;

import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.orchestration.GenerationSession;
import com.rush.rushaicodemother.orchestration.GenerationSessionFactory;
import com.rush.rushaicodemother.orchestration.GenerationSessionRegistry;
import com.rush.rushaicodemother.orchestration.GenerationTaskRequest;
import com.rush.rushaicodemother.orchestration.heavy.HeavyGenerationCoordinator;
import com.rush.rushaicodemother.orchestration.heavy.HeavyGenerationPreparationService;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContext;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContextService;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionFence;
import com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskExecution;
import com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskExecutor;
import com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskRuntimeLifecycleService;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.DurableGenerationTaskRepository;
import com.rush.rushaicodemother.orchestration.runtime.tracing.GenerationTraceContext;
import com.rush.rushaicodemother.orchestration.runtime.tracing.GenerationTraceContextBridge;
import com.rush.rushaicodemother.orchestration.workspace.GenerationExecutionWorkspace;
import com.rush.rushaicodemother.orchestration.workspace.GenerationExecutionWorkspaceService;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspaceExecutionScope;
import com.rush.rushaicodemother.service.app.AppPersistenceService;
import com.rush.rushaicodemother.service.trace.GenerationTraceService;
import com.rush.rushaicodemother.service.user.UserPersistenceService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/** 重新接受决定性批准并恢复确切的持久模型工具调用。 */
@Service
@RequiredArgsConstructor(onConstructor_ = @Autowired)
public class GenerationToolContinuationScheduler {

    private final ToolInvocationCheckpointFactory checkpointFactory;
    private final GenerationExecutionContextService executionContextService;
    private final GenerationSessionFactory sessionFactory;
    private final GenerationSessionRegistry sessionRegistry;
    private final GenerationTaskExecutor taskExecutor;
    private final GenerationTaskRuntimeLifecycleService runtimeLifecycleService;
    private final DurableGenerationTaskRepository durableTaskRepository;
    private final HeavyGenerationCoordinator heavyGenerationCoordinator;
    private final HeavyGenerationPreparationService preparationService;
    private final AppPersistenceService appPersistenceService;
    private final UserPersistenceService userPersistenceService;
    private final GenerationTraceService generationTraceService;
    private final GenerationTraceContextBridge traceContextBridge;
    private final GenerationExecutionWorkspaceService executionWorkspaceService;
    private final GenerationWorkspaceExecutionScope workspaceExecutionScope;

    /**
     * 执行前创建的针对重点测试和遗留调用者的兼容性构造函数
     * 引入了工作空间。 Spring 使用上面的 Lombok 生成的构造函数。
     */
    public GenerationToolContinuationScheduler(
            ToolInvocationCheckpointFactory checkpointFactory,
            GenerationExecutionContextService executionContextService,
            GenerationSessionFactory sessionFactory,
            GenerationSessionRegistry sessionRegistry,
            GenerationTaskExecutor taskExecutor,
            GenerationTaskRuntimeLifecycleService runtimeLifecycleService,
            DurableGenerationTaskRepository durableTaskRepository,
            HeavyGenerationCoordinator heavyGenerationCoordinator,
            HeavyGenerationPreparationService preparationService,
            AppPersistenceService appPersistenceService,
            UserPersistenceService userPersistenceService,
            GenerationTraceService generationTraceService,
            GenerationTraceContextBridge traceContextBridge
    ) {
        this(
                checkpointFactory,
                executionContextService,
                sessionFactory,
                sessionRegistry,
                taskExecutor,
                runtimeLifecycleService,
                durableTaskRepository,
                heavyGenerationCoordinator,
                preparationService,
                appPersistenceService,
                userPersistenceService,
                generationTraceService,
                traceContextBridge,
                null,
                null
        );
    }

    /**
 * 处理调度。
 *
 * @param decision 决策
 */
    public void schedule(ToolApprovalRecord decision) {
        // 先处理前置条件和快速返回分支，避免无效输入进入核心流程。
        if (decision == null || decision.invocationCheckpoint() == null
                || (decision.status() != ToolApprovalStatus.APPROVED
                    && decision.status() != ToolApprovalStatus.CONSUMED
                    && decision.status() != ToolApprovalStatus.REJECTED
                    && decision.status() != ToolApprovalStatus.EXPIRED)) {
            throw new IllegalArgumentException("resumable tool approval decision is required");
        }
        GenerationToolContinuationState state = checkpointFactory.restore(
                decision.invocationCheckpoint());
        validateDecision(decision, state);
        GenerationExecutionContext executionContext = executionContextService
                .getByTaskId(state.taskId())
                .orElseGet(() -> executionContextService.restore(
                        state.execution(), state.executionLimits()));
        GenerationSession session = resolveSession(state, executionContext);
        GenerationExecutionFence executionFence = claimForDispatch(state, session).orElse(null);
        if (executionFence == null) {
            return;
        }
        executionContext.bindExecutionFence(executionFence);
        GenerationExecutionWorkspace executionWorkspace = null;
        // 将可能失败的操作收敛在统一异常边界内，便于清理资源和转换错误。
        try {
            if (executionWorkspaceService != null) {
                executionWorkspace = executionWorkspaceService.register(
                        executionFence,
                        state.appId(),
                        state.preparation().targetType()
                );
                session.bindExecutionWorkspace(executionWorkspace);
            }
            GenerationTaskExecution execution = new GenerationTaskExecution(
                    state.taskId(), session, executionContext, executionFence,
                    state.execution().startedAt());
            Runnable tracedContinuation = traceContextBridge.wrap(
                    resolveTraceContext(state),
                    "generation.tool.continue",
                    Map.of(
                            "generation.task.id", state.taskId(),
                            "generation.app.id", String.valueOf(state.appId()),
                            "generation.user.id", String.valueOf(state.userId()),
                            "generation.route", state.route()
                    ),
                    () -> runInExecutionWorkspace(
                            executionFence,
                            () -> resume(decision, state, session, executionFence)
                    )
            );
            taskExecutor.execute(execution, tracedContinuation);
        } catch (RuntimeException dispatchFailure) {
            if (executionWorkspaceService != null) {
                executionWorkspaceService.clear(
                        executionFence,
                        state.appId(),
                        GenerationExecutionWorkspaceService.CleanupPolicy.DELETE);
            }
            runtimeLifecycleService.restoreWaitingAfterDispatchFailure(
                    executionFence, "approval_dispatch_retry");
            throw dispatchFailure;
        }
    }

    /** 处理{@code resume}。 */
    private void resume(ToolApprovalRecord decision,
                        GenerationToolContinuationState state,
                        GenerationSession session,
                        GenerationExecutionFence executionFence) {
        GenerationTaskRequest request = session.taskRequest();
        if (request == null || request.app() == null
                || !Objects.equals(state.appId(), request.app().getId())) {
            throw new IllegalStateException("tool continuation application context is invalid");
        }
        GenerationWorkspace executionWorkspace = session.executionWorkspace() == null
                ? null
                : session.executionWorkspace().workspace();
        preparationService.restoreToolExecutionContext(
                request.app(), state.preparation(), executionFence, executionWorkspace);
        runtimeLifecycleService.activate(executionFence);
        heavyGenerationCoordinator.resumeAfterToolDecision(decision, state, session);
    }

    private Void runInExecutionWorkspace(GenerationExecutionFence executionFence,
                                         Runnable continuation) {
        if (workspaceExecutionScope == null) {
            continuation.run();
            return null;
        }
        return workspaceExecutionScope.with(executionFence, () -> {
            continuation.run();
            return null;
        });
    }

    /** 以原子方式声明{@code For}{@code Dispatch}。 */
    private Optional<GenerationExecutionFence> claimForDispatch(GenerationToolContinuationState state,
                                                                GenerationSession session) {
        Optional<GenerationExecutionFence> claimed =
                runtimeLifecycleService.requeueAfterApproval(state.taskId());
        if (claimed.isPresent()) {
            return claimed;
        }
        var current = durableTaskRepository.findByTaskId(state.taskId()).orElse(null);
        if (current == null || current.terminal() || current.status()
                != com.rush.rushaicodemother.model.enums.GenerationTaskStatus.WAITING_APPROVAL) {
            return Optional.empty();
        }
        if (current.deadlineAt() != null && !current.deadlineAt().isAfter(Instant.now())) {
            heavyGenerationCoordinator.timeoutWaitingToolApproval(state, session);
            return Optional.empty();
        }
        throw new IllegalStateException("waiting approval task could not be claimed for continuation");
    }

    /** 根据当前上下文解析会话。 */
    private GenerationSession resolveSession(GenerationToolContinuationState state,
                                             GenerationExecutionContext executionContext) {
        GenerationSession existing = sessionRegistry.getByTaskId(state.taskId());
        if (existing != null) {
            if (existing.executionContext() != executionContext) {
                throw new IllegalStateException("local continuation session has a different execution context");
            }
            return existing;
        }
        synchronized (sessionRegistry.lock(state.appId())) {
            existing = sessionRegistry.getByTaskId(state.taskId());
            if (existing != null) {
                return existing;
            }
            sessionRegistry.assertNoActiveSession(state.appId());
            App app = appPersistenceService.findActiveById(state.appId());
            User user = userPersistenceService.findActiveById(state.userId());
            if (app == null || user == null || !Objects.equals(app.getUserId(), user.getId())) {
                throw new IllegalStateException("tool continuation application or owner no longer exists");
            }
            GenerationSession restored = sessionFactory.create(state.preparation(), executionContext);
            restored.bindTaskRequest(new GenerationTaskRequest(
                    app,
                    state.userPrompt(),
                    user
            ));
            restored.recordRoute(state.route());
            restored.bindTraceContext(generationTraceService, state.appId(), state.userId());
            sessionRegistry.put(state.appId(), restored);
            return restored;
        }
    }

    /** 校验{@code ate}决策是否有效。 */
    private void validateDecision(ToolApprovalRecord decision,
                                  GenerationToolContinuationState state) {
        if (!GenerationToolContinuationState.supportsSchemaVersion(state.schemaVersion())
                || !Objects.equals(decision.taskId(), state.taskId())
                || !Objects.equals(decision.appId(), state.appId())
                || !Objects.equals(decision.userId(), state.userId())
                || state.preparation() == null
                || state.execution() == null
                || state.executionLimits() == null
                || state.userPrompt() == null || state.userPrompt().isBlank()
                || state.route() == null || state.route().isBlank()
                || state.codeGenType() == null
                || !Objects.equals(state.taskId(), state.preparation().taskId())
                || !Objects.equals(state.taskId(), state.execution().taskId())
                || !Objects.equals(state.appId(), state.execution().appId())
                || !Objects.equals(state.userId(), state.execution().userId())
                || state.codeGenType() != state.preparation().targetType()) {
            throw new IllegalStateException("tool continuation checkpoint identity is invalid");
        }
    }

    private GenerationTraceContext resolveTraceContext(GenerationToolContinuationState state) {
        if (state.traceContext() != null && !state.traceContext().isEmpty()) {
            return state.traceContext();
        }
        return durableTaskRepository.findCommandByTaskId(state.taskId())
                .map(command -> command.traceContext())
                .orElseGet(GenerationTraceContext::empty);
    }
}
