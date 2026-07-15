package com.rush.rushaicodemother.orchestration.heavy;

import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.GenerationOrchestrationRequest;
import com.rush.rushaicodemother.orchestration.GenerationOrchestrationResult;
import com.rush.rushaicodemother.orchestration.GenerationOrchestrator;
import com.rush.rushaicodemother.orchestration.GenerationPreparation;
import com.rush.rushaicodemother.orchestration.artifact.ChangePlan;
import com.rush.rushaicodemother.orchestration.artifact.GenerationArtifact;
import com.rush.rushaicodemother.orchestration.context.GeneratedProjectContextService;
import com.rush.rushaicodemother.orchestration.routing.HeavyGenerationIntentAssembler;
import com.rush.rushaicodemother.orchestration.routing.HeavyGenerationIntentDecision;
import com.rush.rushaicodemother.orchestration.tool.GenerationToolExecutionContextService;
import com.rush.rushaicodemother.service.GenerationMemoryContextService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.function.Function;
import java.util.function.Supplier;

@Service
@RequiredArgsConstructor
public class HeavyGenerationPreparationService {

    private final HeavyGenerationIntentAssembler heavyGenerationIntentAssembler;
    private final GenerationMemoryContextService generationMemoryContextService;
    private final GenerationOrchestrator generationOrchestrator;
    private final GenerationToolExecutionContextService generationToolExecutionContextService;
    private final GeneratedProjectContextService generatedProjectContextService;

    public GenerationPreparation prepare(App app, String userMessage) {
        GenerationIntent intent = recognizeGenerationIntent(app, userMessage);
        GenerationContextAssembly contextAssembly = assembleGenerationContext(intent);
        GenerationRoutingPlan routingPlan = routeGeneration(intent, contextAssembly);
        return buildGenerationPreparation(intent, routingPlan);
    }

    private GenerationIntent recognizeGenerationIntent(App app, String userMessage) {
        HeavyGenerationIntentDecision decision = heavyGenerationIntentAssembler.assemble(app, userMessage);
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

    private GenerationContextAssembly assembleGenerationContext(GenerationIntent intent) {
        return new GenerationContextAssembly(createProjectContextSupplier(intent.app()));
    }

    private GenerationRoutingPlan routeGeneration(GenerationIntent intent, GenerationContextAssembly contextAssembly) {
        return new GenerationRoutingPlan(
                routingPrompt -> CodeGenTypeEnum.max(intent.currentType(), intent.targetType()),
                contextAssembly
        );
    }

    private GenerationPreparation buildGenerationPreparation(GenerationIntent intent,
                                                             GenerationRoutingPlan routingPlan) {
        CodeGenTypeEnum targetType = intent.targetType() == null
                ? routingPlan.routingFunction().apply(intent.generationMessage())
                : intent.targetType();
        String memoryContext = generationMemoryContextService.buildGenerationMemoryContext(
                intent.app(),
                intent.generationMessage(),
                targetType
        );
        GenerationOrchestrationResult orchestrationResult = generationOrchestrator.prepare(
                new GenerationOrchestrationRequest(
                        intent.app(),
                        intent.generationMessage(),
                        intent.currentType(),
                        intent.generatingStage(),
                        intent.hasGeneratedCode(),
                        routingPlan.contextAssembly().projectContextSupplier(),
                        routingPlan.routingFunction(),
                        memoryContext
                )
        );
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

    private void bindToolExecutionContext(App app, GenerationPreparation preparation) {
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
    }

    private Supplier<String> createProjectContextSupplier(App app) {
        return () -> generatedProjectContextService.build(app);
    }

    private record GenerationIntent(App app,
                                    CodeGenTypeEnum currentType,
                                    CodeGenTypeEnum targetType,
                                    String generationMessage,
                                    String generatingStage,
                                    boolean hasGeneratedCode,
                                    boolean requiresBuild) {
    }

    private record GenerationContextAssembly(Supplier<String> projectContextSupplier) {
    }

    private record GenerationRoutingPlan(Function<String, CodeGenTypeEnum> routingFunction,
                                         GenerationContextAssembly contextAssembly) {
    }
}
