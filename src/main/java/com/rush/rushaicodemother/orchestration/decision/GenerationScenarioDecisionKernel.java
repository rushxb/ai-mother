package com.rush.rushaicodemother.orchestration.decision;

import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.GenerationResourceRequirements;
import com.rush.rushaicodemother.orchestration.GenerationTaskRequest;
import com.rush.rushaicodemother.orchestration.intent.IntentProfile;
import com.rush.rushaicodemother.orchestration.release.GenerationExecutionReleaseIdentityProvider;
import com.rush.rushaicodemother.orchestration.router.GenerationRouteSelection;
import com.rush.rushaicodemother.orchestration.router.GenerationModeRouter;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.function.UnaryOperator;

/**
 * 产生并校验唯一场景决策的深模块。
 *
 * <p>调用方只需提供请求、工程类型与工作区事实；意图、路由、资源、
 * 工具权限和发布身份的组合约束都封装在模块内。</p>
 */
@Component
public class GenerationScenarioDecisionKernel {

    private final GenerationModeRouter generationModeRouter;
    private final GenerationExecutionReleaseIdentityProvider releaseIdentityProvider;

    public GenerationScenarioDecisionKernel(
            GenerationModeRouter generationModeRouter,
            GenerationExecutionReleaseIdentityProvider releaseIdentityProvider) {
        this.generationModeRouter = Objects.requireNonNull(generationModeRouter, "生成路由器不能为空");
        this.releaseIdentityProvider = Objects.requireNonNull(
                releaseIdentityProvider, "生成发布身份模块不能为空");
    }

    public GenerationScenarioDecision decide(GenerationTaskRequest request,
                                             CodeGenTypeEnum targetType,
                                             GenerationWorkspace workspace) {
        GenerationRouteSelection selection = Objects.requireNonNull(
                generationModeRouter.select(request, targetType, workspace),
                "路由器未返回场景选择");
        return freeze(targetType, selection);
    }

    GenerationScenarioDecision decide(GenerationTaskRequest request,
                                      CodeGenTypeEnum targetType,
                                      GenerationWorkspace workspace,
                                      UnaryOperator<IntentProfile> profileRefiner) {
        GenerationRouteSelection selection = Objects.requireNonNull(
                generationModeRouter.select(request, targetType, workspace, profileRefiner),
                "路由器未返回场景选择");
        return freeze(targetType, selection);
    }

    private GenerationScenarioDecision freeze(CodeGenTypeEnum targetType,
                                              GenerationRouteSelection selection) {
        IntentProfile profile = selection.intentProfile();
        boolean readOnly = GenerationScenarioDecision.isReadOnlyOperation(profile.operationType());
        GenerationMutability mutability = readOnly
                ? GenerationMutability.READ_ONLY
                : GenerationMutability.WRITE;
        GenerationResourceRequirements requiredResources = readOnly
                ? GenerationResourceRequirements.none()
                : GenerationResourceRequirements.ofDatabaseRequirement(profile.requiresDatabase());
        GenerationToolPermissionProfile toolPermissions = readOnly
                ? GenerationToolPermissionProfile.READ_ONLY
                : GenerationToolPermissionProfile.WRITE_FENCED;
        String releaseFingerprint = releaseIdentityProvider.current(selection.ruleVersion())
                .releaseFingerprint();
        return new GenerationScenarioDecision(
                profile,
                targetType,
                mutability,
                requiredResources,
                selection.decision(),
                toolPermissions,
                selection.ruleVersion(),
                releaseFingerprint);
    }
}
