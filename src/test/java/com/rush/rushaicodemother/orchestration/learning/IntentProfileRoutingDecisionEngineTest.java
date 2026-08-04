package com.rush.rushaicodemother.orchestration.learning;

import com.rush.rushaicodemother.orchestration.intent.IntentAffectedScope;
import com.rush.rushaicodemother.orchestration.intent.IntentDestructiveRisk;
import com.rush.rushaicodemother.orchestration.intent.IntentOperationType;
import com.rush.rushaicodemother.orchestration.intent.IntentProfile;
import com.rush.rushaicodemother.orchestration.intent.IntentSemanticComplexity;
import com.rush.rushaicodemother.orchestration.intent.IntentValidationRisk;
import com.rush.rushaicodemother.orchestration.router.ExpectedValidationLevel;
import com.rush.rushaicodemother.orchestration.router.GenerationMode;
import com.rush.rushaicodemother.orchestration.router.GenerationModeDecision;
import com.rush.rushaicodemother.orchestration.router.GenerationRoutingDecisionCode;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IntentProfileRoutingDecisionEngineTest {

    private final IntentProfileRoutingDecisionEngine engine = new IntentProfileRoutingDecisionEngine();

    @Test
    void lowRiskSmallFrontendEditShouldUseLightEdit() {
        GenerationModeDecision decision = engine.decide(profile(
                IntentOperationType.EDIT,
                Set.of(IntentAffectedScope.FRONTEND),
                IntentSemanticComplexity.LOW,
                false,
                false,
                IntentDestructiveRisk.LOW,
                2,
                IntentValidationRisk.LOW
        ));

        assertEquals(GenerationMode.LIGHT_EDIT, decision.mode());
        assertEquals(GenerationRoutingDecisionCode.INTENT_PROFILE_LIGHT_EDIT, decision.decisionCode());
        assertEquals(ExpectedValidationLevel.FAST, decision.expectedValidationLevel());
        assertChineseReason(decision);
    }

    @Test
    void ordinaryCreateShouldUseTemplateFirstRoute() {
        GenerationModeDecision decision = engine.decide(profile(
                IntentOperationType.CREATE,
                Set.of(IntentAffectedScope.FRONTEND),
                IntentSemanticComplexity.MEDIUM,
                false,
                false,
                IntentDestructiveRisk.LOW,
                5,
                IntentValidationRisk.MEDIUM
        ));

        assertEquals(GenerationMode.CREATE, decision.mode());
        assertEquals(GenerationRoutingDecisionCode.INTENT_PROFILE_CREATE, decision.decisionCode());
        assertEquals(ExpectedValidationLevel.BUILD, decision.expectedValidationLevel());
        assertChineseReason(decision);
    }

    @Test
    void complexInfrastructureCreateShouldUseHeavyExpert() {
        GenerationModeDecision decision = engine.decide(profile(
                IntentOperationType.CREATE,
                Set.of(IntentAffectedScope.FRONTEND, IntentAffectedScope.INFRASTRUCTURE),
                IntentSemanticComplexity.HIGH,
                true,
                false,
                IntentDestructiveRisk.MEDIUM,
                12,
                IntentValidationRisk.HIGH
        ));

        assertEquals(GenerationMode.HEAVY_EXPERT, decision.mode());
        assertEquals(GenerationRoutingDecisionCode.INTENT_PROFILE_COMPLEX_CREATE, decision.decisionCode());
        assertEquals(ExpectedValidationLevel.EXPERT, decision.expectedValidationLevel());
        assertChineseReason(decision);
    }

    @Test
    void repairAcrossApiAndDatabaseShouldUseAgentEdit() {
        GenerationModeDecision decision = engine.decide(profile(
                IntentOperationType.REPAIR,
                Set.of(IntentAffectedScope.API, IntentAffectedScope.DATABASE),
                IntentSemanticComplexity.MEDIUM,
                true,
                true,
                IntentDestructiveRisk.MEDIUM,
                5,
                IntentValidationRisk.HIGH
        ));

        assertEquals(GenerationMode.AGENT_EDIT, decision.mode());
        assertEquals(GenerationRoutingDecisionCode.INTENT_PROFILE_AGENT_EDIT, decision.decisionCode());
        assertEquals(ExpectedValidationLevel.EXPERT, decision.expectedValidationLevel());
        assertChineseReason(decision);
    }

    @Test
    void highDestructiveExistingChangeShouldUseHeavyExpert() {
        GenerationModeDecision decision = engine.decide(profile(
                IntentOperationType.EDIT,
                Set.of(IntentAffectedScope.BACKEND, IntentAffectedScope.AUTHENTICATION),
                IntentSemanticComplexity.MEDIUM,
                true,
                false,
                IntentDestructiveRisk.HIGH,
                6,
                IntentValidationRisk.HIGH
        ));

        assertEquals(GenerationMode.HEAVY_EXPERT, decision.mode());
        assertEquals(GenerationRoutingDecisionCode.INTENT_PROFILE_HEAVY_EDIT, decision.decisionCode());
        assertEquals(ExpectedValidationLevel.EXPERT, decision.expectedValidationLevel());
        assertChineseReason(decision);
    }

    private IntentProfile profile(IntentOperationType operationType,
                                  Set<IntentAffectedScope> affectedScopes,
                                  IntentSemanticComplexity semanticComplexity,
                                  boolean requiresBackend,
                                  boolean requiresDatabase,
                                  IntentDestructiveRisk destructiveRisk,
                                  int expectedFileCount,
                                  IntentValidationRisk validationRisk) {
        return new IntentProfile(
                operationType,
                affectedScopes,
                semanticComplexity,
                requiresBackend,
                requiresDatabase,
                destructiveRisk,
                expectedFileCount,
                validationRisk,
                0.86
        );
    }

    private void assertChineseReason(GenerationModeDecision decision) {
        assertTrue(decision.reason() != null && decision.reason().matches(".*[\\u4e00-\\u9fff].*"),
                () -> "候选路由原因必须包含中文: " + decision.reason());
    }
}