package com.rush.rushaicodemother.orchestration.heavy;

import com.rush.rushaicodemother.core.builder.BuildExecutionBudgetReservation;
import com.rush.rushaicodemother.core.builder.VueBuildResult;
import com.rush.rushaicodemother.core.builder.VueProjectBuilder;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import org.springframework.stereotype.Component;

import java.util.Objects;

/** Vue 工程构建验证适配器。 */
@Component
public final class VueProjectBuildValidationAdapter
        implements GenerationProjectBuildValidationAdapter {

    private final VueProjectBuilder vueProjectBuilder;

    public VueProjectBuildValidationAdapter(VueProjectBuilder vueProjectBuilder) {
        this.vueProjectBuilder = Objects.requireNonNull(
                vueProjectBuilder, "Vue 项目构建器不能为空");
    }

    @Override
    public CodeGenTypeEnum codeGenType() {
        return CodeGenTypeEnum.VUE_PROJECT;
    }

    @Override
    public ProjectBuildValidationResult validate(
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
}
