package com.rush.rushaicodemother.orchestration.heavy;

import com.rush.rushaicodemother.core.builder.BuildExecutionBudgetReservation;
import com.rush.rushaicodemother.core.builder.VueBuildResult;
import com.rush.rushaicodemother.core.builder.VueProjectBuilder;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.verification.runtime.ProjectRuntimeValidationResult;
import com.rush.rushaicodemother.orchestration.workspace.GeneratedProjectWorkspaceInspection;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import com.rush.rushaicodemother.service.browser.BrowserRuntimeValidationPolicy;
import com.rush.rushaicodemother.service.devserver.DevServerValidationRequest;
import com.rush.rushaicodemother.service.devserver.DevServerValidationResult;
import com.rush.rushaicodemother.service.devserver.DevServerValidationService;
import com.rush.rushaicodemother.service.impl.GeneratedProjectWorkspaceInspector;
import org.springframework.stereotype.Component;

import java.util.Objects;

/** Vue 工程的构建与前端运行时验证 adapter。 */
@Component
public final class VueProjectValidationAdapter implements
        GenerationProjectBuildValidationAdapter,
        GenerationProjectRuntimeValidationAdapter {

    private final VueProjectBuilder vueProjectBuilder;
    private final DevServerValidationService devServerValidationService;

    public VueProjectValidationAdapter(
            VueProjectBuilder vueProjectBuilder,
            DevServerValidationService devServerValidationService
    ) {
        this.vueProjectBuilder = Objects.requireNonNull(
                vueProjectBuilder, "Vue 项目构建器不能为空");
        this.devServerValidationService = Objects.requireNonNull(
                devServerValidationService, "Dev Server 验证器不能为空");
    }

    @Override
    public CodeGenTypeEnum codeGenType() {
        return CodeGenTypeEnum.VUE_PROJECT;
    }

    @Override
    public GeneratedProjectWorkspaceInspection inspect(
            GenerationWorkspace workspace
    ) {
        return GeneratedProjectWorkspaceInspector.inspectVueProject(
                workspace.frontendRootPath());
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

    /**
     * Dev Server 就绪后才发布暂定预览；只有带执行栅栏的任务工作区才能移交会话持有权。
     */
    @Override
    public ProjectRuntimeValidationResult validateRuntime(
            GenerationProjectRuntimeValidationRequest request
    ) {
        DevServerValidationRequest validationRequest = DevServerValidationRequest.of(
                        request.taskId(),
                        request.appId(),
                        request.userId(),
                        codeGenType())
                .withBrowserValidation(BrowserRuntimeValidationPolicy.productionRuntime());
        if (request.executionFence() != null) {
            validationRequest = validationRequest
                    .withExecutionFence(request.executionFence())
                    .withReadyCallback(request.onFrontendReady())
                    .withTaskScopedOwnership();
        }
        DevServerValidationResult result = devServerValidationService.validate(validationRequest);
        return ProjectRuntimeValidationResult.fromDevServer(result);
    }
}
