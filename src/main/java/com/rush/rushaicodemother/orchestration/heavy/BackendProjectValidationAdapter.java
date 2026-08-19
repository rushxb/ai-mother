package com.rush.rushaicodemother.orchestration.heavy;

import com.rush.rushaicodemother.core.builder.BuildExecutionBudgetReservation;
import com.rush.rushaicodemother.core.builder.GoBuildResult;
import com.rush.rushaicodemother.core.builder.GoProjectBuilder;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.verification.runtime.BackendRuntimeValidationResult;
import com.rush.rushaicodemother.orchestration.verification.runtime.GeneratedBackendRuntimeVerifier;
import com.rush.rushaicodemother.orchestration.verification.runtime.GeneratedBackendRuntimeRequest;
import com.rush.rushaicodemother.orchestration.verification.runtime.ProjectRuntimeValidationResult;
import com.rush.rushaicodemother.orchestration.workspace.GeneratedProjectWorkspaceInspection;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import com.rush.rushaicodemother.service.impl.GeneratedProjectWorkspaceInspector;
import org.springframework.stereotype.Component;

import java.util.Objects;

/** Go 后端工程的构建与 HTTP health 运行时验证 adapter。 */
@Component
public final class BackendProjectValidationAdapter implements
        GenerationProjectBuildValidationAdapter,
        GenerationProjectRuntimeValidationAdapter {

    private final GoProjectBuilder goProjectBuilder;
    private final GeneratedBackendRuntimeVerifier backendRuntimeVerifier;

    public BackendProjectValidationAdapter(
            GoProjectBuilder goProjectBuilder,
            GeneratedBackendRuntimeVerifier backendRuntimeVerifier
    ) {
        this.goProjectBuilder = Objects.requireNonNull(
                goProjectBuilder, "Go 项目构建器不能为空");
        this.backendRuntimeVerifier = Objects.requireNonNull(
                backendRuntimeVerifier, "后端运行时验证器不能为空");
    }

    @Override
    public CodeGenTypeEnum codeGenType() {
        return CodeGenTypeEnum.BACKEND_PROJECT;
    }

    @Override
    public GeneratedProjectWorkspaceInspection inspect(
            GenerationWorkspace workspace
    ) {
        return GeneratedProjectWorkspaceInspector.inspectBackendProject(
                workspace.backendRootPath());
    }

    @Override
    public ProjectBuildValidationResult validate(
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

    @Override
    public ProjectRuntimeValidationResult validateRuntime(
            GenerationProjectRuntimeValidationRequest request
    ) {
        BackendRuntimeValidationResult result = backendRuntimeVerifier.verify(
                new GeneratedBackendRuntimeRequest(
                        request.workspace().backendRootPath(),
                        request.maximumDuration(),
                        request.cancellationRequested()));
        return ProjectRuntimeValidationResult.fromBackend(result);
    }
}
