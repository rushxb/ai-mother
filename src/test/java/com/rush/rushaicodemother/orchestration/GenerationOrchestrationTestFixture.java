package com.rush.rushaicodemother.orchestration;

import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.decision.GenerationScenarioDecision;
import com.rush.rushaicodemother.orchestration.intent.IntentProfile;
import com.rush.rushaicodemother.orchestration.router.ExpectedValidationLevel;
import com.rush.rushaicodemother.orchestration.router.FallbackPolicy;
import com.rush.rushaicodemother.orchestration.router.GenerationMode;
import com.rush.rushaicodemother.orchestration.router.GenerationModeDecision;

/**
 * 测试专用的冻结场景请求工厂。
 *
 * <p>测试也必须经由与生产 Heavy 链路相同的单一事实入口，
 * 避免旧测试夹具重新引入 Prompt 路由函数。</p>
 */
public final class GenerationOrchestrationTestFixture {

    private GenerationOrchestrationTestFixture() {
    }

    public static GenerationOrchestrationRequest frozenRequest(
            App app,
            String userMessage,
            CodeGenTypeEnum currentType,
            String generatingStage,
            boolean hasGeneratedCode
    ) {
        return frozenRequest(app, userMessage, currentType, generatingStage, hasGeneratedCode, currentType);
    }

    public static GenerationOrchestrationRequest frozenRequest(
            App app,
            String userMessage,
            CodeGenTypeEnum currentType,
            String generatingStage,
            boolean hasGeneratedCode,
            CodeGenTypeEnum targetType
    ) {
        GenerationModeDecision routeDecision = new GenerationModeDecision(
                GenerationMode.LIGHT_EDIT,
                0.91,
                "test frozen scenario",
                FallbackPolicy.NONE,
                ExpectedValidationLevel.FAST,
                ""
        );
        GenerationScenarioDecision scenarioDecision = GenerationScenarioDecision.restoreLegacy(
                IntentProfile.unknown(),
                targetType,
                GenerationResourceRequirements.none(),
                routeDecision,
                10
        );
        return GenerationOrchestrationRequest.fromFrozenScenario(
                app,
                userMessage,
                currentType,
                generatingStage,
                hasGeneratedCode,
                null,
                null,
                null,
                GenerationPlanningVariant.CURRENT_DAG,
                scenarioDecision
        );
    }
}
