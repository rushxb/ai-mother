package com.rush.rushaicodemother.orchestration.decision;

import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.GenerationResourceRequirements;
import com.rush.rushaicodemother.orchestration.GenerationTaskRequest;
import com.rush.rushaicodemother.orchestration.intent.IntentAffectedScope;
import com.rush.rushaicodemother.orchestration.intent.IntentAmbiguitySignal;
import com.rush.rushaicodemother.orchestration.intent.IntentDestructiveRisk;
import com.rush.rushaicodemother.orchestration.intent.IntentOperationType;
import com.rush.rushaicodemother.orchestration.intent.IntentProfile;
import com.rush.rushaicodemother.orchestration.intent.IntentSemanticComplexity;
import com.rush.rushaicodemother.orchestration.intent.IntentValidationRisk;
import com.rush.rushaicodemother.orchestration.pipeline.GenerationPipeline;
import com.rush.rushaicodemother.orchestration.pipeline.GenerationPipelineCapability;
import com.rush.rushaicodemother.orchestration.pipeline.GenerationPipelineCapabilityException;
import com.rush.rushaicodemother.orchestration.pipeline.GenerationPipelineCapabilityRegistry;
import com.rush.rushaicodemother.orchestration.pipeline.GenerationPipelineOutcome;
import com.rush.rushaicodemother.orchestration.pipeline.GenerationPipelineRequest;
import com.rush.rushaicodemother.orchestration.router.ExpectedValidationLevel;
import com.rush.rushaicodemother.orchestration.router.FallbackPolicy;
import com.rush.rushaicodemother.orchestration.router.GenerationMode;
import com.rush.rushaicodemother.orchestration.router.GenerationModeDecision;
import com.rush.rushaicodemother.orchestration.router.GenerationModeRouter;
import com.rush.rushaicodemother.orchestration.router.GenerationRouteSelection;
import com.rush.rushaicodemother.orchestration.router.GenerationRoutingDecisionCode;
import com.rush.rushaicodemother.orchestration.release.GenerationExecutionReleaseIdentity;
import com.rush.rushaicodemother.orchestration.release.GenerationExecutionReleaseIdentityProvider;
import com.rush.rushaicodemother.orchestration.recipe.GenerationRecipeLibrary;
import com.rush.rushaicodemother.orchestration.skill.GenerationSkillLibrary;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GenerationScenarioDecisionKernelTest {

    private final GenerationModeRouter router = mock(GenerationModeRouter.class);
    private final GenerationExecutionReleaseIdentityProvider releaseIdentityProvider =
            mock(GenerationExecutionReleaseIdentityProvider.class);
    private final GenerationExecutionReleaseIdentity releaseIdentity =
            new GenerationExecutionReleaseIdentity(
                    "a".repeat(40),
                    false,
                    "b".repeat(64),
                    "c".repeat(64),
                    "d".repeat(64),
                    "intent-lexical/test");
    private final GenerationPipelineCapabilityRegistry capabilityRegistry =
            productionCapabilityRegistry();
    private final GenerationScenarioDecisionKernel kernel =
            new GenerationScenarioDecisionKernel(
                    router,
                    releaseIdentityProvider,
                    new GenerationGuidanceSelector(
                            new GenerationRecipeLibrary(),
                            new GenerationSkillLibrary(List.of(), false)
                    ),
                    capabilityRegistry
            );

    @Test
    void writeDecisionMustOwnResourcesPermissionsRouteAndValidationAsOneFact() {
        GenerationTaskRequest request = mock(GenerationTaskRequest.class);
        when(request.message()).thenReturn("新增登录注册管理页面");
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
        when(releaseIdentityProvider.current("intent-lexical/test"))
                .thenReturn(releaseIdentity);

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
        assertEquals(releaseIdentity.releaseFingerprint(), decision.releaseFingerprint());
        assertTrue(decision.guidanceSelection().recipeIds().contains("auth-basic"));
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
        when(releaseIdentityProvider.current("intent-lexical/test"))
                .thenReturn(releaseIdentity);

        GenerationScenarioDecision decision = kernel.decide(
                request, CodeGenTypeEnum.FULL_STACK_PROJECT, workspace);

        assertEquals(GenerationMutability.READ_ONLY, decision.mutability());
        assertFalse(decision.requiredResources().databaseRequired());
        assertEquals(GenerationToolPermissionProfile.READ_ONLY, decision.toolPermissionProfile());
        assertEquals(ExpectedValidationLevel.FAST, decision.validationFloor());
        assertTrue(decision.guidanceSelection().recipes().isEmpty());
        assertTrue(decision.guidanceSelection().skills().isEmpty());
    }

    @Test
    void existingFrontendProjectWithBackendIntentMustFreezeFullStackTarget() {
        GenerationTaskRequest request = mock(GenerationTaskRequest.class);
        GenerationWorkspace workspace = mock(GenerationWorkspace.class);
        when(workspace.exists()).thenReturn(true);
        IntentProfile profile = profile(IntentOperationType.EDIT, true);
        GenerationModeDecision route = GenerationModeDecision.of(
                GenerationMode.HEAVY_EXPERT,
                0.94,
                "现有前端工程新增数据库后端",
                FallbackPolicy.NONE,
                ExpectedValidationLevel.EXPERT);
        when(router.select(request, CodeGenTypeEnum.VUE_PROJECT, workspace))
                .thenReturn(new GenerationRouteSelection(profile, route, "intent-lexical/test"));
        when(releaseIdentityProvider.current("intent-lexical/test"))
                .thenReturn(releaseIdentity);

        GenerationScenarioDecision decision = kernel.decide(
                request, CodeGenTypeEnum.VUE_PROJECT, workspace);

        assertEquals(CodeGenTypeEnum.FULL_STACK_PROJECT, decision.targetType());
    }

    @Test
    void explicitFrameworkMigrationMustFreezeRequestedTargetType() {
        GenerationTaskRequest request = mock(GenerationTaskRequest.class);
        GenerationWorkspace workspace = mock(GenerationWorkspace.class);
        IntentProfile profile = new IntentProfile(
                IntentOperationType.EDIT,
                Set.of(IntentAffectedScope.FRONTEND),
                IntentSemanticComplexity.MEDIUM,
                false,
                false,
                IntentDestructiveRisk.LOW,
                5,
                IntentValidationRisk.MEDIUM,
                0.96,
                IntentAmbiguitySignal.resolved(),
                CodeGenTypeEnum.VUE_PROJECT);
        GenerationModeDecision route = GenerationModeDecision.of(
                GenerationMode.HEAVY_EXPERT,
                0.96,
                "现有 HTML 工程迁移到 Vue",
                FallbackPolicy.NONE,
                ExpectedValidationLevel.BUILD);
        when(router.select(request, CodeGenTypeEnum.HTML, workspace))
                .thenReturn(new GenerationRouteSelection(profile, route, "intent-lexical/test"));
        when(releaseIdentityProvider.current("intent-lexical/test"))
                .thenReturn(releaseIdentity);

        GenerationScenarioDecision decision = kernel.decide(
                request, CodeGenTypeEnum.HTML, workspace);

        assertEquals(CodeGenTypeEnum.VUE_PROJECT, decision.targetType());
    }

    @ParameterizedTest
    @EnumSource(value = CodeGenTypeEnum.class, names = {"HTML", "MULTI_FILE"})
    void createWithoutTemplateOwnerMustNegotiateHeavyFromActualRegistry(
            CodeGenTypeEnum targetType) {
        GenerationTaskRequest request = mock(GenerationTaskRequest.class);
        when(request.message()).thenReturn("创建静态页面");
        GenerationWorkspace workspace = mock(GenerationWorkspace.class);
        IntentProfile profile = simpleCreateProfile(null);
        GenerationModeDecision candidate = GenerationModeDecision.of(
                GenerationMode.CREATE,
                0.95,
                "模板优先创建",
                FallbackPolicy.ESCALATE_TO_HEAVY_EXPERT,
                ExpectedValidationLevel.BUILD);
        when(router.select(request, targetType, workspace))
                .thenReturn(new GenerationRouteSelection(
                        profile, candidate, "intent-lexical/test"));
        when(releaseIdentityProvider.current("intent-lexical/test"))
                .thenReturn(releaseIdentity);

        GenerationScenarioDecision decision = kernel.decide(request, targetType, workspace);

        assertEquals(targetType, decision.targetType());
        assertEquals(GenerationMode.HEAVY_EXPERT, decision.routeDecision().mode());
        assertEquals(FallbackPolicy.NONE, decision.routeDecision().fallbackPolicy());
        assertEquals(GenerationRoutingDecisionCode.CREATE_TEMPLATE_COVERAGE_GAP,
                decision.routeDecision().decisionCode());
        assertEquals(ExpectedValidationLevel.EXPERT, decision.validationFloor());
    }

    @Test
    void capabilityNegotiationMustUseResolvedTargetTypeInsteadOfCurrentType() {
        GenerationTaskRequest request = mock(GenerationTaskRequest.class);
        when(request.message()).thenReturn("把静态站升级为 Vue 项目");
        GenerationWorkspace workspace = mock(GenerationWorkspace.class);
        IntentProfile profile = simpleCreateProfile(CodeGenTypeEnum.VUE_PROJECT);
        GenerationModeDecision candidate = GenerationModeDecision.of(
                GenerationMode.CREATE,
                0.95,
                "模板优先创建",
                FallbackPolicy.ESCALATE_TO_HEAVY_EXPERT,
                ExpectedValidationLevel.BUILD);
        when(router.select(request, CodeGenTypeEnum.HTML, workspace))
                .thenReturn(new GenerationRouteSelection(
                        profile, candidate, "intent-lexical/test"));
        when(releaseIdentityProvider.current("intent-lexical/test"))
                .thenReturn(releaseIdentity);

        GenerationScenarioDecision decision = kernel.decide(
                request, CodeGenTypeEnum.HTML, workspace);

        assertEquals(CodeGenTypeEnum.VUE_PROJECT, decision.targetType());
        assertEquals(GenerationMode.CREATE, decision.routeDecision().mode());
    }

    @Test
    void selectedRouteWithMissingFallbackOwnerMustFailBeforeScenarioFreezes() {
        GenerationTaskRequest request = mock(GenerationTaskRequest.class);
        when(request.message()).thenReturn("创建 Vue 项目");
        GenerationWorkspace workspace = mock(GenerationWorkspace.class);
        IntentProfile profile = simpleCreateProfile(null);
        GenerationModeDecision candidate = GenerationModeDecision.of(
                GenerationMode.CREATE,
                0.95,
                "模板优先创建",
                FallbackPolicy.ESCALATE_TO_HEAVY_EXPERT,
                ExpectedValidationLevel.BUILD);
        when(router.select(request, CodeGenTypeEnum.VUE_PROJECT, workspace))
                .thenReturn(new GenerationRouteSelection(
                        profile, candidate, "intent-lexical/test"));
        GenerationPipelineCapabilityRegistry incompleteRegistry =
                new GenerationPipelineCapabilityRegistry(List.of(
                        pipeline(GenerationPipelineCapability.write(
                                "create",
                                EnumSet.of(IntentOperationType.CREATE),
                                EnumSet.of(CodeGenTypeEnum.VUE_PROJECT),
                                EnumSet.of(GenerationMode.CREATE)))));
        GenerationScenarioDecisionKernel incompleteKernel = new GenerationScenarioDecisionKernel(
                router,
                releaseIdentityProvider,
                new GenerationGuidanceSelector(
                        new GenerationRecipeLibrary(),
                        new GenerationSkillLibrary(List.of(), false)),
                incompleteRegistry);

        assertThrows(GenerationPipelineCapabilityException.class, () -> incompleteKernel.decide(
                request, CodeGenTypeEnum.VUE_PROJECT, workspace));
    }

    @Test
    void frozenDecisionMustRejectTargetThatDropsExplicitMigrationType() {
        IntentProfile profile = new IntentProfile(
                IntentOperationType.EDIT,
                Set.of(IntentAffectedScope.FRONTEND),
                IntentSemanticComplexity.MEDIUM,
                false,
                false,
                IntentDestructiveRisk.LOW,
                5,
                IntentValidationRisk.MEDIUM,
                0.96,
                IntentAmbiguitySignal.resolved(),
                CodeGenTypeEnum.VUE_PROJECT);
        GenerationModeDecision route = GenerationModeDecision.of(
                GenerationMode.HEAVY_EXPERT,
                0.96,
                "现有 HTML 工程迁移到 Vue",
                FallbackPolicy.NONE,
                ExpectedValidationLevel.BUILD);

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> new GenerationScenarioDecision(
                        profile,
                        CodeGenTypeEnum.HTML,
                        GenerationMutability.WRITE,
                        GenerationResourceRequirements.none(),
                        route,
                        GenerationToolPermissionProfile.WRITE_FENCED,
                        "intent-lexical/test",
                        "b".repeat(64)));

        assertEquals("场景目标工程类型必须承载显式迁移诉求", exception.getMessage());
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

    private IntentProfile simpleCreateProfile(CodeGenTypeEnum explicitProjectType) {
        return new IntentProfile(
                IntentOperationType.CREATE,
                Set.of(IntentAffectedScope.FRONTEND),
                IntentSemanticComplexity.LOW,
                false,
                false,
                IntentDestructiveRisk.LOW,
                3,
                IntentValidationRisk.MEDIUM,
                0.95,
                IntentAmbiguitySignal.resolved(),
                explicitProjectType);
    }

    private GenerationPipelineCapabilityRegistry productionCapabilityRegistry() {
        return new GenerationPipelineCapabilityRegistry(List.of(
                pipeline(GenerationPipelineCapability.write(
                        "create",
                        EnumSet.of(IntentOperationType.CREATE),
                        EnumSet.of(
                                CodeGenTypeEnum.VUE_PROJECT,
                                CodeGenTypeEnum.BACKEND_PROJECT,
                                CodeGenTypeEnum.FULL_STACK_PROJECT),
                        EnumSet.of(GenerationMode.CREATE))),
                pipeline(GenerationPipelineCapability.write(
                        "lightweight_edit",
                        EnumSet.of(IntentOperationType.EDIT, IntentOperationType.REPAIR),
                        EnumSet.allOf(CodeGenTypeEnum.class),
                        EnumSet.of(GenerationMode.LIGHT_EDIT))),
                pipeline(GenerationPipelineCapability.write(
                        "agent_edit",
                        EnumSet.of(IntentOperationType.EDIT, IntentOperationType.REPAIR),
                        EnumSet.allOf(CodeGenTypeEnum.class),
                        EnumSet.of(GenerationMode.AGENT_EDIT))),
                pipeline(GenerationPipelineCapability.write(
                        "heavy_generation",
                        EnumSet.of(
                                IntentOperationType.CREATE,
                                IntentOperationType.EDIT,
                                IntentOperationType.REPAIR),
                        EnumSet.allOf(CodeGenTypeEnum.class),
                        EnumSet.of(GenerationMode.HEAVY_EXPERT))),
                pipeline(GenerationPipelineCapability.readOnly(
                        "read_only",
                        EnumSet.of(
                                IntentOperationType.EXPLAIN,
                                IntentOperationType.AUDIT,
                                IntentOperationType.PLAN),
                        EnumSet.allOf(CodeGenTypeEnum.class),
                        EnumSet.of(GenerationMode.READ_ONLY)))));
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
}
