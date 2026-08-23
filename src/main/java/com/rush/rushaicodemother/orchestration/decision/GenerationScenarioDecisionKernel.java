package com.rush.rushaicodemother.orchestration.decision;

import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.GenerationResourceRequirements;
import com.rush.rushaicodemother.orchestration.GenerationTaskRequest;
import com.rush.rushaicodemother.orchestration.intent.IntentAffectedScope;
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
                                             CodeGenTypeEnum currentType,
                                             GenerationWorkspace workspace) {
        GenerationRouteSelection selection = Objects.requireNonNull(
                generationModeRouter.select(request, currentType, workspace),
                "路由器未返回场景选择");
        return freeze(currentType, selection);
    }

    GenerationScenarioDecision decide(GenerationTaskRequest request,
                                      CodeGenTypeEnum currentType,
                                      GenerationWorkspace workspace,
                                      UnaryOperator<IntentProfile> profileRefiner) {
        GenerationRouteSelection selection = Objects.requireNonNull(
                generationModeRouter.select(request, currentType, workspace, profileRefiner),
                "路由器未返回场景选择");
        return freeze(currentType, selection);
    }

    private GenerationScenarioDecision freeze(CodeGenTypeEnum currentType,
                                              GenerationRouteSelection selection) {
        IntentProfile profile = selection.intentProfile();
        boolean readOnly = GenerationScenarioDecision.isReadOnlyOperation(profile.operationType());
        CodeGenTypeEnum targetType = resolveTargetType(currentType, profile, readOnly);
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

    /**
     * 将已有工程能力与本轮结构化影响范围合成为唯一目标类型。
     *
     * <p>这里不再读取原始 Prompt；目标类型与路由、资源、验证下限共同由同一份
     * {@link IntentProfile} 冻结，避免前端工程新增后端时被错误替换成纯后端工程。</p>
     */
    private CodeGenTypeEnum resolveTargetType(CodeGenTypeEnum currentType,
                                              IntentProfile profile,
                                              boolean readOnly) {
        Objects.requireNonNull(currentType, "当前工程类型不能为空");
        if (readOnly) {
            return currentType;
        }
        if (profile.explicitProjectType() != null) {
            return CodeGenTypeEnum.max(currentType, profile.explicitProjectType());
        }
        boolean frontendRequested = profile.affectedScopes().contains(IntentAffectedScope.FRONTEND);
        boolean backendRequested = profile.requiresBackend();
        CodeGenTypeEnum requestedType;
        if (frontendRequested && backendRequested) {
            requestedType = CodeGenTypeEnum.FULL_STACK_PROJECT;
        } else if (backendRequested) {
            requestedType = CodeGenTypeEnum.BACKEND_PROJECT;
        } else if (frontendRequested && currentType == CodeGenTypeEnum.BACKEND_PROJECT) {
            requestedType = CodeGenTypeEnum.VUE_PROJECT;
        } else {
            requestedType = currentType;
        }
        return CodeGenTypeEnum.max(currentType, requestedType);
    }
}
