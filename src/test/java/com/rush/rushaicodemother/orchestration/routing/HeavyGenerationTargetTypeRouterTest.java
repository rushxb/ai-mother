package com.rush.rushaicodemother.orchestration.routing;

import com.rush.rushaicodemother.ai.AiCodeGenTypeRoutingService;
import com.rush.rushaicodemother.ai.AiCodeGenTypeRoutingServiceFactory;
import com.rush.rushaicodemother.ai.intent.BackendIntentDetector;
import com.rush.rushaicodemother.ai.intent.DeterministicCodeGenTypeRouter;
import com.rush.rushaicodemother.config.AiModelRuntimeProperties;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.monitor.GenerationPerformanceMonitorService;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationBudgetKind;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContext;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContextService;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationRuntimeProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class HeavyGenerationTargetTypeRouterTest {

    @Test
    void highConfidenceFrontendUpgradeMustBypassModelAndPreserveBudgets() {
        Fixture fixture = fixture(true);
        GenerationExecutionContext context = fixture.startTask("local-route", CodeGenTypeEnum.HTML);

        CodeGenTypeEnum targetType = fixture.router().resolve(
                "local-route", 101L, "升级为 Vue 工程", CodeGenTypeEnum.HTML, true);

        assertEquals(CodeGenTypeEnum.VUE_PROJECT, targetType);
        assertEquals(0, context.used(GenerationBudgetKind.ROOT_MODEL_ATTEMPT));
        assertEquals(0, context.used(GenerationBudgetKind.MODEL_TURN));
        assertEquals(0, context.used(GenerationBudgetKind.PROVIDER_FAILOVER_ATTEMPT));
        verifyNoInteractions(fixture.factory());
        assertTrue(hasSpan(fixture.performance(), "heavy_intent_routing_local"));
        assertFalse(hasSpan(fixture.performance(), "heavy_intent_routing_model"));
    }

    @Test
    void existingFrontendAddingBackendMustBecomeFullStackWithoutModelCall() {
        Fixture fixture = fixture(true);
        fixture.startTask("backend-upgrade", CodeGenTypeEnum.VUE_PROJECT);

        CodeGenTypeEnum targetType = fixture.router().resolve(
                "backend-upgrade",
                101L,
                "增加 Go 后端 API 和数据库",
                CodeGenTypeEnum.VUE_PROJECT,
                true
        );

        assertEquals(CodeGenTypeEnum.FULL_STACK_PROJECT, targetType);
        verifyNoInteractions(fixture.factory());
    }

    @Test
    void existingBackendAddingFrontendMustBecomeFullStackWithoutModelCall() {
        Fixture fixture = fixture(true);
        GenerationExecutionContext context = fixture.startTask(
                "frontend-upgrade", CodeGenTypeEnum.BACKEND_PROJECT);

        CodeGenTypeEnum targetType = fixture.router().resolve(
                "frontend-upgrade",
                101L,
                "add a Vue frontend page",
                CodeGenTypeEnum.BACKEND_PROJECT,
                true
        );

        assertEquals(CodeGenTypeEnum.FULL_STACK_PROJECT, targetType);
        assertEquals(0, context.used(GenerationBudgetKind.ROOT_MODEL_ATTEMPT));
        verifyNoInteractions(fixture.factory());
    }

    @Test
    void requestWithoutTypeChangeEvidenceMustKeepCurrentTypeWithoutModelCall() {
        Fixture fixture = fixture(true);
        GenerationExecutionContext context = fixture.startTask(
                "keep-current-type", CodeGenTypeEnum.MULTI_FILE);

        CodeGenTypeEnum targetType = fixture.router().resolve(
                "keep-current-type",
                101L,
                "continue improving the current project",
                CodeGenTypeEnum.MULTI_FILE,
                true
        );

        assertEquals(CodeGenTypeEnum.MULTI_FILE, targetType);
        assertEquals(0, context.used(GenerationBudgetKind.ROOT_MODEL_ATTEMPT));
        assertEquals(0, context.used(GenerationBudgetKind.MODEL_TURN));
        verifyNoInteractions(fixture.factory());
    }

    @Test
    void explicitFullStackIntentMustBypassModel() {
        Fixture fixture = fixture(true);
        GenerationExecutionContext context = fixture.startTask(
                "full-stack-upgrade", CodeGenTypeEnum.HTML);

        CodeGenTypeEnum targetType = fixture.router().resolve(
                "full-stack-upgrade",
                101L,
                "upgrade this project to a full-stack application",
                CodeGenTypeEnum.HTML,
                true
        );

        assertEquals(CodeGenTypeEnum.FULL_STACK_PROJECT, targetType);
        assertEquals(0, context.used(GenerationBudgetKind.ROOT_MODEL_ATTEMPT));
        verifyNoInteractions(fixture.factory());
    }

    @Test
    void ambiguousIntentMustUseDeadlineBoundModelAndBudgets() {
        Fixture fixture = fixture(true);
        GenerationExecutionContext context = fixture.startTask(
                "ambiguous-route", CodeGenTypeEnum.VUE_PROJECT);
        executionRoutingService(fixture, CodeGenTypeEnum.FULL_STACK_PROJECT);

        CodeGenTypeEnum targetType = fixture.router().resolve(
                "ambiguous-route",
                101L,
                "创建 Vue 页面并连接 API 接口",
                CodeGenTypeEnum.VUE_PROJECT,
                true
        );

        assertEquals(CodeGenTypeEnum.FULL_STACK_PROJECT, targetType);
        assertEquals(1, context.used(GenerationBudgetKind.ROOT_MODEL_ATTEMPT));
        assertEquals(1, context.used(GenerationBudgetKind.MODEL_TURN));
        verify(fixture.factory()).createExecutionAiCodeGenTypeRoutingService(
                eq(Duration.ofSeconds(20)), any(Runnable.class), any(Runnable.class));
        assertTrue(hasSpan(fixture.performance(), "heavy_intent_routing_model"));
    }

    @Test
    void rollbackSwitchMustRestoreModelRoutingForExplicitIntent() {
        Fixture fixture = fixture(false);
        GenerationExecutionContext context = fixture.startTask(
                "model-route", CodeGenTypeEnum.HTML);
        executionRoutingService(fixture, CodeGenTypeEnum.VUE_PROJECT);

        CodeGenTypeEnum targetType = fixture.router().resolve(
                "model-route", 101L, "升级为 Vue 工程", CodeGenTypeEnum.HTML, true);

        assertEquals(CodeGenTypeEnum.VUE_PROJECT, targetType);
        assertEquals(1, context.used(GenerationBudgetKind.ROOT_MODEL_ATTEMPT));
        assertEquals(1, context.used(GenerationBudgetKind.MODEL_TURN));
        assertFalse(hasSpan(fixture.performance(), "heavy_intent_routing_local"));
        assertTrue(hasSpan(fixture.performance(), "heavy_intent_routing_model"));
    }

    @Test
    void fullStackProjectMustNotSpendModelBudgetOnAmbiguousTypeIntent() {
        Fixture fixture = fixture(true);
        GenerationExecutionContext context = fixture.startTask(
                "full-stack-route", CodeGenTypeEnum.FULL_STACK_PROJECT);

        CodeGenTypeEnum targetType = fixture.router().resolve(
                "full-stack-route",
                101L,
                "调整 Vue 页面并同步 API 接口",
                CodeGenTypeEnum.FULL_STACK_PROJECT,
                true
        );

        assertEquals(CodeGenTypeEnum.FULL_STACK_PROJECT, targetType);
        assertEquals(0, context.used(GenerationBudgetKind.ROOT_MODEL_ATTEMPT));
        verifyNoInteractions(fixture.factory());
    }

    private Fixture fixture(boolean localFirstEnabled) {
        AiCodeGenTypeRoutingServiceFactory factory = mock(AiCodeGenTypeRoutingServiceFactory.class);
        AiModelRuntimeProperties modelProperties = new AiModelRuntimeProperties();
        modelProperties.setLocalFirstHeavyRoutingEnabled(localFirstEnabled);
        GenerationRuntimeProperties runtimeProperties = new GenerationRuntimeProperties();
        runtimeProperties.setModelCallTimeout(Duration.ofSeconds(20));
        GenerationExecutionContextService contexts =
                new GenerationExecutionContextService(runtimeProperties);
        GenerationPerformanceMonitorService performance = new GenerationPerformanceMonitorService();
        HeavyGenerationTargetTypeRouter router = new HeavyGenerationTargetTypeRouter(
                factory,
                new BackendIntentDetector(),
                new DeterministicCodeGenTypeRouter(),
                modelProperties,
                contexts,
                performance
        );
        return new Fixture(router, factory, contexts, performance);
    }

    private AiCodeGenTypeRoutingService executionRoutingService(
            Fixture fixture,
            CodeGenTypeEnum routedType
    ) {
        AiCodeGenTypeRoutingService routingService = mock(AiCodeGenTypeRoutingService.class);
        AtomicReference<Runnable> beforeModelTurn = new AtomicReference<>();
        when(fixture.factory().createExecutionAiCodeGenTypeRoutingService(
                any(Duration.class), any(Runnable.class), any(Runnable.class)))
                .thenAnswer(invocation -> {
                    beforeModelTurn.set(invocation.getArgument(1));
                    return routingService;
                });
        when(routingService.routeCodeGenType(any(String.class)))
                .thenAnswer(invocation -> {
                    beforeModelTurn.get().run();
                    return routedType;
                });
        return routingService;
    }

    private boolean hasSpan(GenerationPerformanceMonitorService performance, String stage) {
        return performance.getSummary(10).getRecentTasks().getFirst().getSpans().stream()
                .anyMatch(span -> stage.equals(span.getStage()));
    }

    private record Fixture(
            HeavyGenerationTargetTypeRouter router,
            AiCodeGenTypeRoutingServiceFactory factory,
            GenerationExecutionContextService contexts,
            GenerationPerformanceMonitorService performance
    ) {
        private GenerationExecutionContext startTask(String taskId, CodeGenTypeEnum currentType) {
            performance.startTask(taskId, 101L, 22L, "heavy_generation", currentType.getValue());
            return contexts.start(taskId, 101L, 22L);
        }
    }
}
