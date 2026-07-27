package com.rush.rushaicodemother.orchestration.routing;

import cn.hutool.core.util.StrUtil;
import com.rush.rushaicodemother.constant.AppConstant;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.exception.ThrowUtils;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.edit.GenerationEditRouteResult;
import com.rush.rushaicodemother.orchestration.edit.GenerationEditRouteService;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspaceService;
import com.rush.rushaicodemother.service.AppDatabaseResourceService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

/**
 * 在 GenerationModeRouter 选中 HEAVY_EXPERT 后组装重型生成意图。
 */
@Service
public class HeavyGenerationIntentAssembler {

    private static final List<String> BUILD_KEYWORDS = List.of(
            "build", "构建", "打包", "编译", "测试", "lint", "校验", "发布", "npm", "vite",
            "工程化", "vue工程", "路由", "依赖", "配置", "api", "接口"
    );

    private final HeavyGenerationTargetTypeRouter targetTypeRouter;
    private final AppDatabaseResourceService appDatabaseResourceService;
    private final GenerationEditRouteService generationEditRouteService;
    private final GenerationWorkspaceService generationWorkspaceService;

    public HeavyGenerationIntentAssembler(
            HeavyGenerationTargetTypeRouter targetTypeRouter,
            AppDatabaseResourceService appDatabaseResourceService,
            GenerationEditRouteService generationEditRouteService,
            GenerationWorkspaceService generationWorkspaceService) {
        this.targetTypeRouter = targetTypeRouter;
        this.appDatabaseResourceService = appDatabaseResourceService;
        this.generationEditRouteService = generationEditRouteService;
        this.generationWorkspaceService = generationWorkspaceService;
    }

    public HeavyGenerationIntentDecision assemble(App app, String userMessage) {
        return assemble(null, app, userMessage);
    }

    public HeavyGenerationIntentDecision assemble(String taskId, App app, String userMessage) {
        ThrowUtils.throwIf(app == null || app.getId() == null, ErrorCode.PARAMS_ERROR, "应用参数错误");
        CodeGenTypeEnum currentType = CodeGenTypeEnum.getEnumByValue(app.getCodeGenType());
        ThrowUtils.throwIf(currentType == null, ErrorCode.PARAMS_ERROR, "应用代码生成类型错误");

        GenerationWorkspace workspace = generationWorkspaceService.resolve(app, currentType);
        boolean hasGeneratedCode = workspace.exists();
        String generationMessage = appDatabaseResourceService.appendGenerationInstructionIfEnabled(app, userMessage);
        String generatingStage = hasGeneratedCode
                ? AppConstant.GENERATING_STAGE_UPDATE
                : AppConstant.GENERATING_STAGE_CREATE;

        GenerationEditRouteResult editRoute = generationEditRouteService.route(app, userMessage);
        if (editRoute.isLightweightEdit()) {
            return new HeavyGenerationIntentDecision(
                    GenerationRoute.LIGHTWEIGHT_EDIT,
                    editRoute.reason(),
                    editRoute.confidence(),
                    currentType,
                    currentType,
                    generationMessage,
                    generatingStage,
                    hasGeneratedCode,
                    editRoute.requiresBuild()
            );
        }

        CodeGenTypeEnum routedType = targetTypeRouter.resolve(
                taskId, app.getId(), userMessage, currentType, hasGeneratedCode);
        CodeGenTypeEnum targetType = CodeGenTypeEnum.max(currentType, routedType);
        boolean requiresBuild = requiresBuildValidation(generationMessage, currentType, targetType, hasGeneratedCode);
        return new HeavyGenerationIntentDecision(
                GenerationRoute.HEAVY_GENERATION,
                editRoute.reason(),
                editRoute.confidence(),
                currentType,
                targetType,
                generationMessage,
                generatingStage,
                hasGeneratedCode,
                requiresBuild
        );
    }

    private boolean requiresBuildValidation(String message,
                                            CodeGenTypeEnum currentType,
                                            CodeGenTypeEnum targetType,
                                            boolean hasGeneratedCode) {
        if (targetType == CodeGenTypeEnum.BACKEND_PROJECT) {
            return true;
        }
        if (targetType != CodeGenTypeEnum.VUE_PROJECT && targetType != CodeGenTypeEnum.FULL_STACK_PROJECT) {
            return false;
        }
        String normalized = StrUtil.blankToDefault(message, "").toLowerCase(Locale.ROOT);
        if (BUILD_KEYWORDS.stream().anyMatch(normalized::contains)) {
            return true;
        }
        return hasGeneratedCode && currentType != null && currentType.canUpgradeTo(targetType);
    }
}
