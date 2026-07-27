package com.rush.rushaicodemother.orchestration.heavy;

import com.rush.rushaicodemother.core.builder.BuildExecutionBudgetReservation;
import com.rush.rushaicodemother.core.builder.GoBuildResult;
import com.rush.rushaicodemother.core.builder.GoProjectBuilder;
import com.rush.rushaicodemother.core.builder.VueBuildResult;
import com.rush.rushaicodemother.core.builder.VueProjectBuilder;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContextService;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionPolicyException;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** 按项目类型编排前端、后端或全栈构建门禁。 */
@Service
@RequiredArgsConstructor
public class GenerationProjectBuildValidationService {

    private final VueProjectBuilder vueProjectBuilder;
    private final GoProjectBuilder goProjectBuilder;
    private final GenerationExecutionContextService executionContextService;

    public ProjectBuildValidationResult validate(
            GenerationWorkspace workspace,
            CodeGenTypeEnum codeGenType,
            String taskId
    ) {
        Objects.requireNonNull(workspace, "生成工作区不能为空");
        Objects.requireNonNull(codeGenType, "代码生成类型不能为空");
        BuildExecutionBudgetReservation budgetReservation =
                BuildExecutionBudgetReservation.forTask(executionContextService, taskId);
        return switch (codeGenType) {
            case VUE_PROJECT -> validateVue(workspace, taskId, budgetReservation);
            case BACKEND_PROJECT -> validateGo(workspace, taskId, budgetReservation);
            case FULL_STACK_PROJECT -> validateFullStack(workspace, taskId, budgetReservation);
            default -> throw new IllegalArgumentException("当前项目类型不支持构建门禁: " + codeGenType.getValue());
        };
    }

    private ProjectBuildValidationResult validateVue(
            GenerationWorkspace workspace,
            String taskId,
            BuildExecutionBudgetReservation budgetReservation
    ) {
        VueBuildResult result = vueProjectBuilder.buildProjectWithResult(
                workspace.frontendRootPath().toString(),
                taskId,
                budgetReservation
        );
        return ProjectBuildValidationResult.fromVue(result);
    }

    private ProjectBuildValidationResult validateGo(
            GenerationWorkspace workspace,
            String taskId,
            BuildExecutionBudgetReservation budgetReservation
    ) {
        if (workspace.backendRootPath() == null) {
            return ProjectBuildValidationResult.fromGo(GoBuildResult.invalid(
                    workspace.canonicalRootPath().toString(),
                    "后端工作区不可用"
            ));
        }
        GoBuildResult result = goProjectBuilder.buildProjectWithResult(
                workspace.backendRootPath().toString(),
                taskId,
                budgetReservation
        );
        return ProjectBuildValidationResult.fromGo(result);
    }

    private ProjectBuildValidationResult validateFullStack(
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
                        validateGo(workspace, taskId, budgetReservation)
                ));
                frontendFuture = completionService.submit(() -> new ComponentValidation(
                        Component.FRONTEND,
                        validateVue(workspace, taskId, budgetReservation)
                ));

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
                        workspace.canonicalRootPath().toString(),
                        backend,
                        frontend
                );
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
