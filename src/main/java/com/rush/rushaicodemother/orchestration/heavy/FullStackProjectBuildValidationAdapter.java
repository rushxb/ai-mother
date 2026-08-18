package com.rush.rushaicodemother.orchestration.heavy;

import com.rush.rushaicodemother.core.builder.BuildExecutionBudgetReservation;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContextService;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionPolicyException;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** 全栈工程适配器，并发复用前端和后端适配器完成同一轮质量门禁。 */
@Component
public final class FullStackProjectBuildValidationAdapter
        implements GenerationProjectBuildValidationAdapter {

    private final VueProjectBuildValidationAdapter frontendAdapter;
    private final BackendProjectBuildValidationAdapter backendAdapter;
    private final GenerationExecutionContextService executionContextService;

    public FullStackProjectBuildValidationAdapter(
            VueProjectBuildValidationAdapter frontendAdapter,
            BackendProjectBuildValidationAdapter backendAdapter,
            GenerationExecutionContextService executionContextService
    ) {
        this.frontendAdapter = Objects.requireNonNull(
                frontendAdapter, "前端构建验证适配器不能为空");
        this.backendAdapter = Objects.requireNonNull(
                backendAdapter, "后端构建验证适配器不能为空");
        this.executionContextService = Objects.requireNonNull(
                executionContextService, "生成执行上下文服务不能为空");
    }

    @Override
    public CodeGenTypeEnum codeGenType() {
        return CodeGenTypeEnum.FULL_STACK_PROJECT;
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
