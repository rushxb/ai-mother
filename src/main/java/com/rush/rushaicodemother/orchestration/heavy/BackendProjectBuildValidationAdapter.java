package com.rush.rushaicodemother.orchestration.heavy;

import com.rush.rushaicodemother.core.builder.BuildExecutionBudgetReservation;
import com.rush.rushaicodemother.core.builder.GoBuildResult;
import com.rush.rushaicodemother.core.builder.GoProjectBuilder;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import org.springframework.stereotype.Component;

import java.util.Objects;

/** Go 后端工程构建验证适配器。 */
@Component
public final class BackendProjectBuildValidationAdapter
        implements GenerationProjectBuildValidationAdapter {

    private final GoProjectBuilder goProjectBuilder;

    public BackendProjectBuildValidationAdapter(GoProjectBuilder goProjectBuilder) {
        this.goProjectBuilder = Objects.requireNonNull(
                goProjectBuilder, "Go 项目构建器不能为空");
    }

    @Override
    public CodeGenTypeEnum codeGenType() {
        return CodeGenTypeEnum.BACKEND_PROJECT;
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
}
