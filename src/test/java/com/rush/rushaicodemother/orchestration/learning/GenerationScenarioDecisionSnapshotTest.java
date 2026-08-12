package com.rush.rushaicodemother.orchestration.learning;

import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.GenerationResourceRequirements;
import com.rush.rushaicodemother.orchestration.intent.IntentAffectedScope;
import com.rush.rushaicodemother.orchestration.intent.IntentAmbiguitySignal;
import com.rush.rushaicodemother.orchestration.intent.IntentDestructiveRisk;
import com.rush.rushaicodemother.orchestration.intent.IntentOperationType;
import com.rush.rushaicodemother.orchestration.intent.IntentProfile;
import com.rush.rushaicodemother.orchestration.intent.IntentResolutionDimension;
import com.rush.rushaicodemother.orchestration.intent.IntentSemanticComplexity;
import com.rush.rushaicodemother.orchestration.intent.IntentValidationRisk;
import com.rush.rushaicodemother.orchestration.router.ExpectedValidationLevel;
import com.rush.rushaicodemother.orchestration.router.FallbackPolicy;
import com.rush.rushaicodemother.orchestration.router.GenerationMode;
import com.rush.rushaicodemother.orchestration.router.GenerationRoutingDecisionCode;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.GenerationTaskCommand;
import com.rush.rushaicodemother.orchestration.runtime.tracing.GenerationTraceContext;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class GenerationScenarioDecisionSnapshotTest {

    private static final Instant NOW = Instant.parse("2026-08-12T00:00:00Z");

    @Test
    void sameStructuredScenarioMustProduceStableSignatureWithoutIdentityOrPrompt() {
        IntentProfile firstProfile = profile(
                Set.of(IntentAffectedScope.DATABASE, IntentAffectedScope.FRONTEND), 5);
        IntentProfile reorderedProfile = profile(
                Set.of(IntentAffectedScope.FRONTEND, IntentAffectedScope.DATABASE), 5);

        GenerationScenarioDecisionSnapshot first = GenerationScenarioDecisionSnapshot.from(
                command("task-secret-a", 11L, 21L, "绝密需求 alpha", firstProfile));
        GenerationScenarioDecisionSnapshot second = GenerationScenarioDecisionSnapshot.from(
                command("task-secret-b", 12L, 22L, "绝密需求 beta", reorderedProfile));

        assertEquals(first.intentSignature(), second.intentSignature());
        assertEquals(first.evidenceJson(), second.evidenceJson());
        assertEquals(64, first.intentSignature().length());
        assertEquals("intent-profile-v1", first.profileVersion());
        assertEquals("routing-policy-v1", first.decisionVersion());
        assertEquals("routing-policy-v1@task-command-v" + GenerationTaskCommand.CURRENT_SCHEMA_VERSION,
                first.releaseIdentity());
        String persistedFacts = first.evidenceJson() + first.alternativesJson();
        assertFalse(persistedFacts.contains("绝密需求"));
        assertFalse(persistedFacts.contains("task-secret"));
        assertFalse(persistedFacts.contains("11"));
        assertFalse(persistedFacts.contains("21"));
    }

    @Test
    void materiallyDifferentStructuredScenarioMustProduceDifferentSignature() {
        GenerationScenarioDecisionSnapshot medium = GenerationScenarioDecisionSnapshot.from(
                command("task-a", 1L, 2L, "修改页面", profile(Set.of(IntentAffectedScope.FRONTEND), 5)));
        GenerationScenarioDecisionSnapshot large = GenerationScenarioDecisionSnapshot.from(
                command("task-b", 1L, 2L, "修改页面", profile(Set.of(IntentAffectedScope.FRONTEND), 8)));

        assertNotEquals(medium.intentSignature(), large.intentSignature());
    }

    private GenerationTaskCommand command(String taskId,
                                          Long appId,
                                          Long userId,
                                          String prompt,
                                          IntentProfile profile) {
        return new GenerationTaskCommand(
                GenerationTaskCommand.CURRENT_SCHEMA_VERSION,
                taskId, appId, userId, 100L, prompt, CodeGenTypeEnum.VUE_PROJECT,
                GenerationMode.AGENT_EDIT, 0.86, "结构化路由", FallbackPolicy.NONE,
                ExpectedValidationLevel.BUILD, "", GenerationRoutingDecisionCode.INTENT_PROFILE_AGENT_EDIT,
                null, GenerationTraceContext.empty(), NOW, NOW.plusSeconds(600),
                GenerationResourceRequirements.none(), profile, null);
    }

    private IntentProfile profile(Set<IntentAffectedScope> scopes, int expectedFileCount) {
        return new IntentProfile(
                IntentOperationType.EDIT,
                scopes,
                IntentSemanticComplexity.MEDIUM,
                true,
                true,
                IntentDestructiveRisk.LOW,
                expectedFileCount,
                IntentValidationRisk.HIGH,
                0.86,
                new IntentAmbiguitySignal(
                        Set.of(IntentResolutionDimension.SEMANTIC_COMPLEXITY,
                                IntentResolutionDimension.OPERATION_TYPE),
                        false,
                        false));
    }
}
