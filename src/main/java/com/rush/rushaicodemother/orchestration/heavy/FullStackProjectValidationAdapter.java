package com.rush.rushaicodemother.orchestration.heavy;

import com.rush.rushaicodemother.core.builder.BuildExecutionBudgetReservation;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContextService;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionPolicyException;
import com.rush.rushaicodemother.orchestration.verification.runtime.FullStackRuntimeValidationResult;
import com.rush.rushaicodemother.orchestration.verification.runtime.GeneratedFullStackRuntimeVerifier;
import com.rush.rushaicodemother.orchestration.verification.runtime.ProjectRuntimeValidationResult;
import com.rush.rushaicodemother.orchestration.workspace.GeneratedProjectWorkspaceInspection;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import com.rush.rushaicodemother.service.browser.BrowserRuntimeValidationPolicy;
import com.rush.rushaicodemother.service.devserver.DevServerValidationRequest;
import com.rush.rushaicodemother.service.impl.GeneratedProjectWorkspaceInspector;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** 全栈工程的并发构建与前后端联合运行时验证 adapter。 */
@Component
public final class FullStackProjectValidationAdapter implements
        GenerationProjectBuildValidationAdapter,
        GenerationProjectRuntimeValidationAdapter {

    private final VueProjectValidationAdapter frontendAdapter;
    private final BackendProjectValidationAdapter backendAdapter;
    private final GenerationExecutionContextService executionContextService;
    private final GeneratedFullStackRuntimeVerifier fullStackRuntimeVerifier;

    public FullStackProjectValidationAdapter(
            VueProjectValidationAdapter frontendAdapter,
            BackendProjectValidationAdapter backendAdapter,
            GenerationExecutionContextService executionContextService,
            GeneratedFullStackRuntimeVerifier fullStackRuntimeVerifier
    ) {
        this.frontendAdapter = Objects.requireNonNull(
                frontendAdapter, "前端工程验证适配器不能为空");
        this.backendAdapter = Objects.requireNonNull(
                backendAdapter, "后端工程验证适配器不能为空");
        this.executionContextService = Objects.requireNonNull(
                executionContextService, "生成执行上下文服务不能为空");
        this.fullStackRuntimeVerifier = Objects.requireNonNull(
                fullStackRuntimeVerifier, "全栈运行时验证器不能为空");
    }

    @Override
    public CodeGenTypeEnum codeGenType() {
        return CodeGenTypeEnum.FULL_STACK_PROJECT;
    }

    @Override
    public GeneratedProjectWorkspaceInspection inspect(
            GenerationWorkspace workspace
    ) {
        return GeneratedProjectWorkspaceInspector.inspectFullStackProject(
                workspace.canonicalRootPath());
    }

    @Override
    public ProjectBuildValidationResult validate(
            GenerationWorkspace workspace,
            String taskId,
            BuildExecutionBudgetReservation budgetReservation
    ) {
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            ExecutorCompletionService<ComponentValidation> completionService =
                    new ExecutorCompletionService<>(executor);
            Future<ComponentValidation> backendFuture = null;
            Future<ComponentValidation> frontendFuture = null;
            try {
                backendFuture = completionService.submit(() -> new ComponentValidation(
                        Component.BACKEND,
                        backendAdapter.validate(workspace, taskId, budgetReservation)
                ));
                frontendFuture = completionService.submit(() -> new ComponentValidation(
                        Component.FRONTEND,
                        frontendAdapter.validate(workspace, taskId, budgetReservation)
                ));
                return collect(workspace, completionService);
            } catch (InterruptedException exception) {
                cancel(backendFuture, frontendFuture);
                Thread.currentThread().interrupt();
                executionContextService.assertCanContinue(taskId);
                throw new GenerationExecutionPolicyException("全栈构建验证被中断");
            } catch (ExecutionException exception) {
                cancel(backendFuture, frontendFuture);
                throw propagate(exception);
            } catch (RuntimeException | Error exception) {
                cancel(backendFuture, frontendFuture);
                throw exception;
            }
        }
    }

    /**
     * 全栈前端会话只在联合验证窗口内存活，不发布后端已经关闭的半失效暂定预览。
     */
    @Override
    public ProjectRuntimeValidationResult validateRuntime(
            GenerationProjectRuntimeValidationRequest request
    ) {
        DevServerValidationRequest frontendRequest = DevServerValidationRequest.of(
                request.taskId(),
                request.appId(),
                request.userId(),
                codeGenType()
        );
        if (request.executionFence() != null) {
            frontendRequest = frontendRequest.withExecutionFence(request.executionFence());
        }
        FullStackRuntimeValidationResult result = fullStackRuntimeVerifier.verify(
                request.workspace().backendRootPath(),
                frontendRequest,
                BrowserRuntimeValidationPolicy.productionRuntime()
        );
        return ProjectRuntimeValidationResult.fromFullStack(result);
    }

    private ProjectBuildValidationResult collect(
            GenerationWorkspace workspace,
            ExecutorCompletionService<ComponentValidation> completionService
    ) throws InterruptedException, ExecutionException {
        ProjectBuildValidationResult backend = null;
        ProjectBuildValidationResult frontend = null;
        for (int completed = 0; completed < 2; completed++) {
            ComponentValidation validation = completionService.take().get();
            if (validation.component() == Component.BACKEND) {
                backend = validation.result();
            } else {
                frontend = validation.result();
            }
        }
        return ProjectBuildValidationResult.fullStack(
                workspace.canonicalRootPath().toString(), backend, frontend);
    }

    private RuntimeException propagate(ExecutionException exception) {
        Throwable cause = exception.getCause();
        if (cause instanceof RuntimeException runtimeException) {
            return runtimeException;
        }
        if (cause instanceof Error error) {
            throw error;
        }
        return new IllegalStateException("全栈构建验证执行失败", cause);
    }

    private void cancel(Future<?>... futures) {
        for (Future<?> future : futures) {
            if (future != null && !future.isDone()) {
                future.cancel(true);
            }
        }
    }

    private enum Component {
        BACKEND,
        FRONTEND
    }

    private record ComponentValidation(
            Component component,
            ProjectBuildValidationResult result
    ) {
        private ComponentValidation {
            Objects.requireNonNull(component, "构建组件不能为空");
            Objects.requireNonNull(result, "构建验证结果不能为空");
        }
    }
}
