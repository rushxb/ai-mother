package com.rush.rushaicodemother.orchestration.decision;

import com.rush.rushaicodemother.config.AiModelRuntimeProperties;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.monitor.MonitorContextHolder;
import com.rush.rushaicodemother.orchestration.GenerationTaskRequest;
import com.rush.rushaicodemother.orchestration.intent.IntentAffectedScope;
import com.rush.rushaicodemother.orchestration.intent.IntentAmbiguitySignal;
import com.rush.rushaicodemother.orchestration.intent.IntentClarificationRefiner;
import com.rush.rushaicodemother.orchestration.intent.IntentDestructiveRisk;
import com.rush.rushaicodemother.orchestration.intent.IntentOperationType;
import com.rush.rushaicodemother.orchestration.intent.IntentProfile;
import com.rush.rushaicodemother.orchestration.intent.IntentResolutionDimension;
import com.rush.rushaicodemother.orchestration.intent.IntentSemanticComplexity;
import com.rush.rushaicodemother.orchestration.intent.IntentValidationRisk;
import com.rush.rushaicodemother.orchestration.pipeline.GenerationPipelineCapabilityRegistry;
import com.rush.rushaicodemother.orchestration.router.ExpectedValidationLevel;
import com.rush.rushaicodemother.orchestration.router.FallbackPolicy;
import com.rush.rushaicodemother.orchestration.router.GenerationMode;
import com.rush.rushaicodemother.orchestration.router.GenerationModeDecision;
import com.rush.rushaicodemother.orchestration.router.GenerationModeRouter;
import com.rush.rushaicodemother.orchestration.router.GenerationRouteSelection;
import com.rush.rushaicodemother.orchestration.release.GenerationExecutionReleaseIdentity;
import com.rush.rushaicodemother.orchestration.release.GenerationExecutionReleaseIdentityProvider;
import com.rush.rushaicodemother.orchestration.recipe.GenerationRecipeLibrary;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationBudgetKind;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContext;
import com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskAdmissionService;
import com.rush.rushaicodemother.orchestration.skill.GenerationSkillLibrary;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GenerationScenarioPreflightTest {

    private static final Instant NOW = Instant.parse("2026-08-15T01:00:00Z");

    @Test
    void boundedClarificationMustRunAfterIdentityAndGateThenRefreezeTheWholeDecision() {
        App app = App.builder()
                .id(10L)
                .userId(20L)
                .tenantId(30L)
                .codeGenType(CodeGenTypeEnum.FULL_STACK_PROJECT.getValue())
                .build();
        GenerationTaskRequest request = new GenerationTaskRequest(
                app, "ambiguous request", User.builder().id(20L).build());
        GenerationWorkspace workspace = mock(GenerationWorkspace.class);
        IntentProfile initialProfile = ambiguousProfile(IntentOperationType.EDIT);
        IntentProfile refinedProfile = ambiguousProfile(IntentOperationType.AUDIT);
        GenerationModeRouter router = mock(GenerationModeRouter.class);
        when(router.select(eq(request), eq(CodeGenTypeEnum.FULL_STACK_PROJECT), eq(workspace), any()))
                .thenAnswer(invocation -> {
                    UnaryOperator<IntentProfile> refinement = invocation.getArgument(3);
                    IntentProfile effectiveProfile = refinement.apply(initialProfile);
                    GenerationModeDecision route = GenerationModeDecision.of(
                            GenerationMode.READ_ONLY,
                            0.91,
                            "preflight clarified audit",
                            FallbackPolicy.NONE,
                            ExpectedValidationLevel.FAST);
                    return new GenerationRouteSelection(
                            effectiveProfile, route, "intent-lexical/preflight-test");
                });
        GenerationExecutionReleaseIdentityProvider releaseIdentityProvider =
                mock(GenerationExecutionReleaseIdentityProvider.class);
        when(releaseIdentityProvider.current("intent-lexical/preflight-test"))
                .thenReturn(new GenerationExecutionReleaseIdentity(
                        "a".repeat(40), false, "b".repeat(64), "c".repeat(64),
                        "d".repeat(64), "intent-lexical/preflight-test"));
        GenerationScenarioDecisionKernel kernel =
                new GenerationScenarioDecisionKernel(
                        router,
                        releaseIdentityProvider,
                        new GenerationGuidanceSelector(
                                new GenerationRecipeLibrary(),
                                new GenerationSkillLibrary(List.of(), false)
                        ),
                        readOnlyCapabilityRegistry()
                );

        GenerationTaskAdmissionService admissionService = mock(GenerationTaskAdmissionService.class);
        IntentClarificationRefiner clarificationRefiner = mock(IntentClarificationRefiner.class);
        when(clarificationRefiner.canRefine(initialProfile)).thenReturn(true);
        AtomicReference<String> observedTaskId = new AtomicReference<>();
        when(clarificationRefiner.refine(eq(initialProfile), eq("ambiguous request"),
                eq("task-preflight-1"), any(GenerationExecutionContext.class)))
                .thenAnswer(invocation -> {
                    GenerationExecutionContext context = invocation.getArgument(3);
                    observedTaskId.set(MonitorContextHolder.getContext().getTaskId());
                    context.consume(GenerationBudgetKind.ROOT_MODEL_ATTEMPT);
                    context.consume(GenerationBudgetKind.MODEL_TURN);
                    context.consume(GenerationBudgetKind.PROVIDER_FAILOVER_ATTEMPT);
                    return refinedProfile;
                });
        AiModelRuntimeProperties runtimeProperties = new AiModelRuntimeProperties();
        runtimeProperties.setIntentClarificationEnabled(true);
        GenerationScenarioPreflight preflight = new GenerationScenarioPreflight(
                kernel,
                clarificationRefiner,
                admissionService,
                runtimeProperties,
                Clock.fixed(NOW, ZoneOffset.UTC));

        GenerationScenarioPreflightResult result = preflight.prepare(
                "task-preflight-1",
                NOW,
                request,
                CodeGenTypeEnum.FULL_STACK_PROJECT,
                workspace);

        GenerationScenarioDecision decision = result.scenarioDecision();
        assertEquals(IntentOperationType.AUDIT, decision.operation());
        assertEquals(GenerationMode.READ_ONLY, decision.routeDecision().mode());
        assertEquals(GenerationMutability.READ_ONLY, decision.mutability());
        assertFalse(decision.requiredResources().databaseRequired());
        assertEquals(GenerationToolPermissionProfile.READ_ONLY, decision.toolPermissionProfile());
        assertEquals(1, result.usage().rootModelAttempts());
        assertEquals(1, result.usage().modelTurns());
        assertEquals(1, result.usage().providerFailoverAttempts());
        assertTrue(result.creditReserved());
        assertEquals("task-preflight-1", observedTaskId.get());

        InOrder order = inOrder(admissionService, clarificationRefiner);
        order.verify(admissionService).assertMayPreflight(
                "task-preflight-1", request, CodeGenTypeEnum.FULL_STACK_PROJECT, initialProfile);
        order.verify(clarificationRefiner).refine(
                eq(initialProfile), eq("ambiguous request"), eq("task-preflight-1"), any());
        verify(router).select(eq(request), eq(CodeGenTypeEnum.FULL_STACK_PROJECT),
                eq(workspace), any());
        assertSame(refinedProfile, decision.intentProfile());
    }

    private IntentProfile ambiguousProfile(IntentOperationType operationType) {
        return new IntentProfile(
                operationType,
                Set.of(IntentAffectedScope.BACKEND, IntentAffectedScope.DATABASE),
                IntentSemanticComplexity.MEDIUM,
                true,
                true,
                IntentDestructiveRisk.LOW,
                4,
                IntentValidationRisk.MEDIUM,
                0.55,
                new IntentAmbiguitySignal(
                        Set.of(
                                IntentResolutionDimension.OPERATION_TYPE,
                                IntentResolutionDimension.SEMANTIC_COMPLEXITY),
                        false,
                        false));
    }

    private GenerationPipelineCapabilityRegistry readOnlyCapabilityRegistry() {
        GenerationPipelineCapabilityRegistry registry =
                mock(GenerationPipelineCapabilityRegistry.class);
        when(registry.supports(any(), any(), any(), any())).thenReturn(true);
        return registry;
    }
}
