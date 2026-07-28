package com.rush.rushaicodemother.orchestration.heavy;

import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.GenerationOrchestrationRequest;
import com.rush.rushaicodemother.orchestration.GenerationOrchestrationResult;
import com.rush.rushaicodemother.orchestration.GenerationOrchestrator;
import com.rush.rushaicodemother.orchestration.GenerationPreparation;
import com.rush.rushaicodemother.orchestration.artifact.ChangePlan;
import com.rush.rushaicodemother.orchestration.artifact.GenerationArtifact;
import com.rush.rushaicodemother.orchestration.context.GenerationMemoryContextOverlapExecutor;
import com.rush.rushaicodemother.orchestration.context.GenerationMemoryContextOverlapExecutor.MemoryContextHandle;
import com.rush.rushaicodemother.orchestration.routing.HeavyGenerationIntentAssembler;
import com.rush.rushaicodemother.orchestration.routing.HeavyGenerationIntentDecision;
import com.rush.rushaicodemother.orchestration.tool.GenerationToolExecutionContextService;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionFence;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspaceService;
import com.rush.rushaicodemother.service.GenerationMemoryContextService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.function.Function;

@Service
@RequiredArgsConstructor(onConstructor_ = @Autowired)
public class HeavyGenerationPreparationService {

    private final HeavyGenerationIntentAssembler heavyGenerationIntentAssembler;
    private final GenerationMemoryContextService generationMemoryContextService;
    private final GenerationOrchestrator generationOrchestrator;
    private final GenerationToolExecutionContextService generationToolExecutionContextService;
    private final GenerationWorkspaceService generationWorkspaceService;
    private final GenerationMemoryContextOverlapExecutor memoryContextOverlapExecutor;

    public GenerationPreparation prepare(App app, String userMessage) {
        return prepare(null, app, userMessage);
    }

    /**
     * 使用执行运行时已保留的标识准备生成任务。
     */
    public GenerationPreparation prepare(String taskId, App app, String userMessage) {
        GenerationIntent intent = recognizeGenerationIntent(taskId, app, userMessage);
        GenerationRoutingPlan routingPlan = routeGeneration(intent);
        return buildGenerationPreparation(taskId, intent, routingPlan);
    }

    /** 返回{@code recognize}生成{@code Intent}。 */
    private GenerationIntent recognizeGenerationIntent(String taskId, App app, String userMessage) {
        HeavyGenerationIntentDecision decision = taskId == null || taskId.isBlank()
                ? heavyGenerationIntentAssembler.assemble(app, userMessage)
                : heavyGenerationIntentAssembler.assemble(taskId, app, userMessage);
        return new GenerationIntent(
                app,
                decision.currentType(),
                decision.targetType(),
                decision.generationMessage(),
                decision.generatingStage(),
                decision.hasGeneratedCode(),
                decision.requiresBuild()
        );
    }

    private GenerationRoutingPlan routeGeneration(GenerationIntent intent) {
        return new GenerationRoutingPlan(
                routingPrompt -> CodeGenTypeEnum.max(intent.currentType(), intent.targetType()));
    }

    /** 构建并返回生成{@code Preparation}。 */
    private GenerationPreparation buildGenerationPreparation(String taskId,
                                                             GenerationIntent intent,
                                                             GenerationRoutingPlan routingPlan) {
        CodeGenTypeEnum targetType = intent.targetType() == null
                ? routingPlan.routingFunction().apply(intent.generationMessage())
                : intent.targetType();
        GenerationOrchestrationResult orchestrationResult;
        try (MemoryContextHandle memoryContext = memoryContextOverlapExecutor.start(
                taskId,
                () -> generationMemoryContextService.buildGenerationMemoryContext(
                        taskId, intent.app(), intent.generationMessage(), targetType))) {
            orchestrationResult = generationOrchestrator.prepare(
                    new GenerationOrchestrationRequest(
                            intent.app(),
                            intent.generationMessage(),
                            intent.currentType(),
                            intent.generatingStage(),
                            intent.hasGeneratedCode(),
                            routingPlan.routingFunction(),
                            null,
                            memoryContext::resolve,
                            taskId
                    )
            );
        }
        GenerationPreparation preparation = new GenerationPreparation(
                orchestrationResult.originalType(),
                orchestrationResult.targetType(),
                orchestrationResult.upgradeRequired(),
                orchestrationResult.generatingStage(),
                orchestrationResult.enhancedMessage(),
                orchestrationResult.events(),
                orchestrationResult.artifacts(),
                orchestrationResult.qualityGateResult(),
                orchestrationResult.timings(),
                orchestrationResult.taskId()
        );
        bindToolExecutionContext(intent.app(), preparation);
        return preparation;
    }

    /** 绑定工具执行上下文。 */
    private void bindToolExecutionContext(App app, GenerationPreparation preparation) {
        // 先处理前置条件和快速返回分支，避免无效输入进入核心流程。
        if (app == null || app.getId() == null || preparation == null) {
            return;
        }
        GenerationArtifact changePlanArtifact = preparation.artifact("change_plan");
        ChangePlan changePlan = changePlanArtifact == null ? null : ChangePlan.fromPayload(changePlanArtifact.payload());
        boolean allowUnplannedWrite = changePlan != null && "project_bootstrap".equals(changePlan.changeScope());
        String generationMode = allowUnplannedWrite ? "full_generation" : "patch_first";
        generationToolExecutionContextService.bindChangePlan(
                app.getId(),
                preparation.taskId(),
                generationMode,
                preparation.targetType(),
                changePlan,
                allowUnplannedWrite,
                "orchestration_context"
        );
        if (generationWorkspaceService == null) {
            return;
        }
        // 将可能失败的操作收敛在统一异常边界内，便于清理资源和转换错误。
        try {
            generationToolExecutionContextService.bindWorkspace(
                    app.getId(),
                    preparation.taskId(),
                    generationWorkspaceService.resolve(app.getId(), preparation.targetType())
            );
        } catch (RuntimeException ignored) {
            // 传统的非托管准备调用者没有执行范围。
        }
    }

    public void restoreToolExecutionContext(App app, GenerationPreparation preparation) {
        bindToolExecutionContext(app, preparation);
    }

    /** 恢复工具策略状态并将其固定到确切的恢复执行工作区。 */
    public void restoreToolExecutionContext(App app,
                                            GenerationPreparation preparation,
                                            GenerationExecutionFence executionFence,
                                            GenerationWorkspace workspace) {
        bindToolExecutionContext(app, preparation);
        if (app != null && preparation != null && executionFence != null) {
            generationToolExecutionContextService.bindExecutionFence(
                    app.getId(), preparation.taskId(), executionFence);
            if (workspace != null) {
                generationToolExecutionContextService.bindWorkspace(
                        app.getId(), preparation.taskId(), workspace, executionFence);
            }
        }
    }

    private record GenerationIntent(App app,
                                    CodeGenTypeEnum currentType,
                                    CodeGenTypeEnum targetType,
                                    String generationMessage,
                                    String generatingStage,
                                    boolean hasGeneratedCode,
                                    boolean requiresBuild) {
    }

    private record GenerationRoutingPlan(Function<String, CodeGenTypeEnum> routingFunction) {
    }
}
