package com.rush.rushaicodemother.orchestration.routing;

import com.rush.rushaicodemother.infrastructure.diagnostic.LogExceptionSanitizer;
import cn.hutool.core.util.StrUtil;
import com.rush.rushaicodemother.ai.AiCodeGenTypeRoutingService;
import com.rush.rushaicodemother.ai.AiCodeGenTypeRoutingServiceFactory;
import com.rush.rushaicodemother.constant.AppConstant;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.exception.ThrowUtils;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.edit.GenerationEditRouteResult;
import com.rush.rushaicodemother.orchestration.edit.GenerationEditRouteService;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspaceService;
import com.rush.rushaicodemother.service.AppDatabaseResourceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

/**
 * Assembles the legacy heavy-generation intent after GenerationModeRouter has selected HEAVY_EXPERT.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HeavyGenerationIntentAssembler {

    private static final List<String> BUILD_KEYWORDS = List.of(
            "build", "构建", "打包", "编译", "测试", "lint", "校验", "发布", "npm", "vite",
            "工程化", "vue工程", "路由", "依赖", "配置", "api", "接口"
    );

    private final AiCodeGenTypeRoutingServiceFactory aiCodeGenTypeRoutingServiceFactory;
    private final AppDatabaseResourceService appDatabaseResourceService;
    private final GenerationEditRouteService generationEditRouteService;
    private final GenerationWorkspaceService generationWorkspaceService;

    public HeavyGenerationIntentDecision assemble(App app, String userMessage) {
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

        CodeGenTypeEnum routedType = routeTargetType(app, generationMessage, currentType);
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

    private CodeGenTypeEnum routeTargetType(App app, String routingPrompt, CodeGenTypeEnum currentType) {
        try {
            AiCodeGenTypeRoutingService routingService = aiCodeGenTypeRoutingServiceFactory.createAiCodeGenTypeRoutingService();
            CodeGenTypeEnum routedType = routingService.routeCodeGenType(routingPrompt);
            return routedType == null ? currentType : routedType;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("重型生成意图装配失败，沿用当前模式，appId: {}", app.getId(), LogExceptionSanitizer.sanitize(e));
            return currentType;
        }
    }

    private boolean requiresBuildValidation(String message,
                                            CodeGenTypeEnum currentType,
                                            CodeGenTypeEnum targetType,
                                            boolean hasGeneratedCode) {
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
