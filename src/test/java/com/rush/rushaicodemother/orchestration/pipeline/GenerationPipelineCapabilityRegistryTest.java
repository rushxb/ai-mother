package com.rush.rushaicodemother.orchestration.pipeline;

import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.GenerationResourceRequirements;
import com.rush.rushaicodemother.orchestration.decision.GenerationMutability;
import com.rush.rushaicodemother.orchestration.decision.GenerationScenarioDecision;
import com.rush.rushaicodemother.orchestration.decision.GenerationToolPermissionProfile;
import com.rush.rushaicodemother.orchestration.intent.IntentAffectedScope;
import com.rush.rushaicodemother.orchestration.intent.IntentDestructiveRisk;
import com.rush.rushaicodemother.orchestration.intent.IntentOperationType;
import com.rush.rushaicodemother.orchestration.intent.IntentProfile;
import com.rush.rushaicodemother.orchestration.intent.IntentSemanticComplexity;
import com.rush.rushaicodemother.orchestration.intent.IntentValidationRisk;
import com.rush.rushaicodemother.orchestration.router.ExpectedValidationLevel;
import com.rush.rushaicodemother.orchestration.router.FallbackPolicy;
import com.rush.rushaicodemother.orchestration.router.GenerationMode;
import com.rush.rushaicodemother.orchestration.router.GenerationModeDecision;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerationPipelineCapabilityRegistryTest {

    @Test
    void registryMustResolvePipelineFromFrozenScenarioFacts() {
        GenerationPipelineCapability capability = writeCapability(
                "agent_edit", IntentOperationType.EDIT, GenerationMode.AGENT_EDIT);
        GenerationPipelineCapabilityRegistry registry =
                new GenerationPipelineCapabilityRegistry(List.of(pipeline(capability)));

        GenerationPipelineCapability resolved = registry.requireCapability(scenario(
                IntentOperationType.EDIT, GenerationMode.AGENT_EDIT));

        assertSame(capability, resolved);
    }

    @Test
    void missingRouteTypeCombinationMustFailClosed() {
        GenerationPipelineCapabilityRegistry registry =
                new GenerationPipelineCapabilityRegistry(List.of(pipeline(writeCapability(
                        "agent_edit", IntentOperationType.EDIT, GenerationMode.AGENT_EDIT))));

        assertThrows(GenerationPipelineCapabilityException.class, () -> registry.requireCapability(
                scenario(IntentOperationType.CREATE, GenerationMode.CREATE)));
    }

    @Test
    void readOnlyLookupMustBeDerivedFromRegisteredPipelineDeclarations() {
        GenerationPipelineCapability capability = writeCapability(
                "heavy_generation", IntentOperationType.CREATE, GenerationMode.HEAVY_EXPERT);
        GenerationPipelineCapabilityRegistry registry =
                new GenerationPipelineCapabilityRegistry(List.of(pipeline(capability)));

        assertSame(capability, registry.requireCapability(
                IntentOperationType.CREATE,
                GenerationMutability.WRITE,
                CodeGenTypeEnum.VUE_PROJECT,
                GenerationMode.HEAVY_EXPERT));
        assertTrue(registry.supports(
                IntentOperationType.CREATE,
                GenerationMutability.WRITE,
                CodeGenTypeEnum.VUE_PROJECT,
                GenerationMode.HEAVY_EXPERT));
    }

    @Test
    void duplicateKeyAndModeMustFailDuringRegistryConstruction() {
        GenerationPipeline first = pipeline(writeCapability(
                "agent_edit_a", IntentOperationType.EDIT, GenerationMode.AGENT_EDIT));
        GenerationPipeline second = pipeline(writeCapability(
                "agent_edit_b", IntentOperationType.EDIT, GenerationMode.AGENT_EDIT));

        assertThrows(IllegalStateException.class,
                () -> new GenerationPipelineCapabilityRegistry(List.of(first, second)));
    }

    private GenerationPipelineCapability writeCapability(
            String route,
            IntentOperationType operation,
            GenerationMode mode
    ) {
        return GenerationPipelineCapability.write(
                route,
                EnumSet.of(operation),
                EnumSet.of(CodeGenTypeEnum.VUE_PROJECT),
                EnumSet.of(mode));
    }

    private GenerationPipeline pipeline(GenerationPipelineCapability capability) {
        return new GenerationPipeline() {
            @Override
            public String route() {
                return capability.route();
            }

            @Override
            public GenerationPipelineCapability capability() {
                return capability;
            }

            @Override
            public GenerationPipelineOutcome execute(GenerationPipelineRequest request) {
                throw new UnsupportedOperationException("测试管线不执行任务");
            }
        };
    }

    private GenerationScenarioDecision scenario(
            IntentOperationType operation,
            GenerationMode mode
    ) {
        IntentProfile profile = new IntentProfile(
                operation,
                Set.of(IntentAffectedScope.FRONTEND),
                IntentSemanticComplexity.LOW,
                false,
                false,
                IntentDestructiveRisk.LOW,
                1,
                IntentValidationRisk.LOW,
                0.95);
        GenerationModeDecision routeDecision = GenerationModeDecision.of(
                mode,
                0.95,
                "测试路由",
                FallbackPolicy.NONE,
                ExpectedValidationLevel.BUILD);
        return new GenerationScenarioDecision(
                profile,
                CodeGenTypeEnum.VUE_PROJECT,
                GenerationMutability.WRITE,
                GenerationResourceRequirements.none(),
                routeDecision,
                GenerationToolPermissionProfile.WRITE_FENCED,
                "test-rule",
                "test-release");
    }
}
