package com.rush.rushaicodemother.orchestration.intent;

import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.GenerationTaskRequest;
import com.rush.rushaicodemother.orchestration.pipeline.GenerationPipelineRequest;
import com.rush.rushaicodemother.orchestration.plan.GenerationExecutionPlanner;
import com.rush.rushaicodemother.orchestration.router.ExpectedValidationLevel;
import com.rush.rushaicodemother.orchestration.router.FallbackPolicy;
import com.rush.rushaicodemother.orchestration.router.GenerationMode;
import com.rush.rushaicodemother.orchestration.router.GenerationModeDecision;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 澄清阶段回归。
 *
 * <p>场景 preflight 已经冻结全部决策，worker 内的历史阶段必须严格无操作。</p>
 */
class IntentClarificationStageTest {

    @Test
    void workerMustNeverClarifyAgainAfterScenarioDecisionIsFrozen() {
        IntentClarificationRefiner refiner = mock(IntentClarificationRefiner.class);
        GenerationExecutionPlanner executionPlanner = mock(GenerationExecutionPlanner.class);
        IntentClarificationStage stage = new IntentClarificationStage(refiner, executionPlanner);
        GenerationPipelineRequest request = request(IntentSemanticComplexity.MEDIUM);

        assertSame(request, stage.apply(request));

        verifyNoInteractions(refiner, executionPlanner);
    }

    private GenerationPipelineRequest request(IntentSemanticComplexity complexity) {
        App app = App.builder()
                .id(10L)
                .userId(20L)
                .tenantId(1L)
                .codeGenType(CodeGenTypeEnum.VUE_PROJECT.getValue())
                .build();
        User user = User.builder().id(20L).build();
        IntentProfile profile = new IntentProfile(
                IntentOperationType.EDIT,
                Set.of(IntentAffectedScope.FRONTEND),
                complexity,
                false,
                false,
                IntentDestructiveRisk.LOW,
                2,
                IntentValidationRisk.LOW,
                0.8
        );
        GenerationModeDecision decision = GenerationModeDecision.of(
                GenerationMode.AGENT_EDIT,
                0.8,
                "clarification-stage-test",
                FallbackPolicy.ESCALATE_TO_HEAVY_EXPERT,
                ExpectedValidationLevel.BUILD
        );
        GenerationPipelineRequest planless = new GenerationPipelineRequest(
                new GenerationTaskRequest(app, "把登录页做得好看点", user),
                CodeGenTypeEnum.VUE_PROJECT,
                workspace(),
                profile,
                decision
        );
        return planless;
    }

    private GenerationWorkspace workspace() {
        Path root = Path.of("target/test-clarification-stage-workspace");
        return new GenerationWorkspace(
                10L,
                CodeGenTypeEnum.VUE_PROJECT,
                root,
                root,
                true,
                root,
                root,
                Set.of(),
                Set.of()
        );
    }
}
