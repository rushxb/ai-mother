package com.rush.rushaicodemother.orchestration.routing;

import com.rush.rushaicodemother.constant.AppConstant;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.exception.ThrowUtils;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.decision.GenerationScenarioDecision;
import com.rush.rushaicodemother.orchestration.router.GenerationMode;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspaceService;
import com.rush.rushaicodemother.service.AppDatabaseResourceService;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * 在 GenerationModeRouter 选中 HEAVY_EXPERT 后组装重型生成意图。
 */
@Service
public class HeavyGenerationIntentAssembler {

    private final AppDatabaseResourceService appDatabaseResourceService;
    private final GenerationWorkspaceService generationWorkspaceService;

    public HeavyGenerationIntentAssembler(
            AppDatabaseResourceService appDatabaseResourceService,
            GenerationWorkspaceService generationWorkspaceService) {
        this.appDatabaseResourceService = Objects.requireNonNull(
                appDatabaseResourceService, "应用数据库资源服务不能为空");
        this.generationWorkspaceService = Objects.requireNonNull(
                generationWorkspaceService, "生成工作区服务不能为空");
    }

    /**
     * 从准入阶段已经冻结的场景事实组装 Heavy 执行输入。
     *
     * <p>这里只补充工作区状态和平台数据库指令，不再执行编辑分流、关键词判定或模型类型路由，
     * 避免同一任务在排队后产生第二份场景结论。</p>
     */
    public HeavyGenerationIntentDecision assemble(App app,
                                                   String userMessage,
                                                   GenerationScenarioDecision scenarioDecision) {
        ThrowUtils.throwIf(app == null || app.getId() == null, ErrorCode.PARAMS_ERROR, "应用参数错误");
        GenerationScenarioDecision frozenDecision = Objects.requireNonNull(
                scenarioDecision, "冻结场景决策不能为空");
        if (frozenDecision.routeDecision().mode() != GenerationMode.HEAVY_EXPERT) {
            throw new IllegalArgumentException("Heavy 准备阶段只能消费 HEAVY_EXPERT 场景决策");
        }
        CodeGenTypeEnum currentType = CodeGenTypeEnum.getEnumByValue(app.getCodeGenType());
        ThrowUtils.throwIf(currentType == null, ErrorCode.PARAMS_ERROR, "应用代码生成类型错误");
        if (CodeGenTypeEnum.max(currentType, frozenDecision.targetType()) != frozenDecision.targetType()) {
            throw new IllegalArgumentException("冻结场景决策不得降低应用工程类型");
        }

        GenerationWorkspace workspace = generationWorkspaceService.resolve(app, currentType);
        boolean hasGeneratedCode = workspace.exists();
        String generationMessage = appDatabaseResourceService
                .appendGenerationInstructionIfEnabled(app, userMessage);
        String generatingStage = hasGeneratedCode
                ? AppConstant.GENERATING_STAGE_UPDATE
                : AppConstant.GENERATING_STAGE_CREATE;
        return new HeavyGenerationIntentDecision(
                frozenDecision.routeDecision().route(),
                frozenDecision.routeDecision().reason(),
                frozenDecision.routeDecision().confidence(),
                currentType,
                frozenDecision.targetType(),
                generationMessage,
                generatingStage,
                hasGeneratedCode
        );
    }

}
