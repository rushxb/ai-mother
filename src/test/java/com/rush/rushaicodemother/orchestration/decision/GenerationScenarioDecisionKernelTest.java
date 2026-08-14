package com.rush.rushaicodemother.orchestration.decision;

import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.GenerationTaskRequest;
import com.rush.rushaicodemother.orchestration.benchmark.evidence.GenerationRuntimeConfigurationFingerprintService;
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
import com.rush.rushaicodemother.orchestration.router.GenerationModeRouter;
import com.rush.rushaicodemother.orchestration.router.GenerationRouteSelection;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GenerationScenarioDecisionKernelTest {

    private final GenerationModeRouter router = mock(GenerationModeRouter.class);
    private final GenerationRuntimeConfigurationFingerprintService fingerprintService =
            mock(GenerationRuntimeConfigurationFingerprintService.class);
    private final GenerationScenarioDecisionKernel kernel =
            new GenerationScenarioDecisionKernel(router, fingerprintService);

    @Test
    void writeDecisionMustOwnResourcesPermissionsRouteAndValidationAsOneFact() {
        GenerationTaskRequest request = mock(GenerationTaskRequest.class);
        GenerationWorkspace workspace = mock(GenerationWorkspace.class);
        IntentProfile profile = profile(IntentOperationType.EDIT, true);
        GenerationModeDecision route = GenerationModeDecision.of(
                GenerationMode.HEAVY_EXPERT,
                0.92,
                "数据库跨层改修",
                FallbackPolicy.NONE,
                ExpectedValidationLevel.EXPERT);
        when(router.select(request, CodeGenTypeEnum.FULL_STACK_PROJECT, workspace))
                .thenReturn(new GenerationRouteSelection(profile, route, "intent-lexical/test"));
        when(fingerprintService.currentFingerprint()).thenReturn("runtime-policy/test");

        GenerationScenarioDecision decision = kernel.decide(
                request, CodeGenTypeEnum.FULL_STACK_PROJECT, workspace);

        assertEquals(IntentOperationType.EDIT, decision.operation());
        assertEquals(GenerationMutability.WRITE, decision.mutability());
        assertTrue(decision.requiredResources().databaseRequired());
        assertEquals(GenerationToolPermissionProfile.WRITE_FENCED, decision.toolPermissionProfile());
        assertEquals(ExpectedValidationLevel.EXPERT, decision.validationFloor());
        assertEquals(Set.of(IntentAffectedScope.BACKEND, IntentAffectedScope.DATABASE),
                decision.contextHints());
        assertEquals("intent-lexical/test", decision.ruleVersion());
        assertEquals(64, decision.releaseFingerprint().length());
    }

    @Test
    void readOnlyDecisionMustNeverProvisionResourcesEvenWhenAnalysisMentionsDatabase() {
        GenerationTaskRequest request = mock(GenerationTaskRequest.class);
        GenerationWorkspace workspace = mock(GenerationWorkspace.class);
        IntentProfile profile = profile(IntentOperationType.AUDIT, true);
        GenerationModeDecision route = GenerationModeDecision.of(
                GenerationMode.READ_ONLY,
                0.96,
                "只读数据库审计",
                FallbackPolicy.NONE,
                ExpectedValidationLevel.FAST);
        when(router.select(request, CodeGenTypeEnum.FULL_STACK_PROJECT, workspace))
                .thenReturn(new GenerationRouteSelection(profile, route, "intent-lexical/test"));
        when(fingerprintService.currentFingerprint()).thenReturn("runtime-policy/test");

        GenerationScenarioDecision decision = kernel.decide(
                request, CodeGenTypeEnum.FULL_STACK_PROJECT, workspace);

        assertEquals(GenerationMutability.READ_ONLY, decision.mutability());
        assertFalse(decision.requiredResources().databaseRequired());
        assertEquals(GenerationToolPermissionProfile.READ_ONLY, decision.toolPermissionProfile());
        assertEquals(ExpectedValidationLevel.FAST, decision.validationFloor());
    }

    private IntentProfile profile(IntentOperationType operationType, boolean requiresDatabase) {
        return new IntentProfile(
                operationType,
                Set.of(IntentAffectedScope.BACKEND, IntentAffectedScope.DATABASE),
                IntentSemanticComplexity.HIGH,
                true,
                requiresDatabase,
                IntentDestructiveRisk.LOW,
                8,
                IntentValidationRisk.HIGH,
                0.92);
    }
}
