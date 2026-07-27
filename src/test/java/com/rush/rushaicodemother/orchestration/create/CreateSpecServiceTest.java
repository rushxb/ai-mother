package com.rush.rushaicodemother.orchestration.create;

import com.rush.rushaicodemother.ai.AiCreateSpecService;
import com.rush.rushaicodemother.ai.AiCreateSpecServiceFactory;
import com.rush.rushaicodemother.ai.model.CreateSpec;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationBudgetKind;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionCancelledException;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContext;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContextService;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationRuntimeProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CreateSpecServiceTest {

    @Test
    void fallbackReasonMustNotExposeModelExceptionDetails() {
        AiCreateSpecServiceFactory serviceFactory = mock(AiCreateSpecServiceFactory.class);
        when(serviceFactory.createService())
                .thenThrow(new IllegalStateException("provider-api-key=secret-value"));
        CreateSpecService service = new CreateSpecService(serviceFactory, new CreateSpecNormalizer());
        CreateGenerationPlan plan = plan();
        SlotGroup group = plan.slotGroups().getFirst();

        CreateSpecService.SpecResult result = service.generate("创建一个官网", plan, group);

        assertEquals("local_spec_fallback:create_spec_exception", result.reason());
        assertFalse(result.reason().contains("secret-value"));
        assertFalse(result.spec().toString().contains("secret-value"));
    }

    @Test
    void managedSpecCallMustConsumeTaskBudgetsAndUseClampedTimeout() {
        GenerationRuntimeProperties properties = new GenerationRuntimeProperties();
        properties.setModelCallTimeout(Duration.ofSeconds(20));
        GenerationExecutionContextService contexts = new GenerationExecutionContextService(properties);
        GenerationExecutionContext context = contexts.start("create-spec-task", 11L, 22L);
        AiCreateSpecServiceFactory serviceFactory = mock(AiCreateSpecServiceFactory.class);
        AiCreateSpecService model = mock(AiCreateSpecService.class);
        AtomicReference<Runnable> beforeModelTurn = new AtomicReference<>();
        when(serviceFactory.createExecutionService(
                any(Duration.class), any(Runnable.class), any(Runnable.class)))
                .thenAnswer(invocation -> {
                    beforeModelTurn.set(invocation.getArgument(1));
                    return model;
                });
        when(model.generateSpec(anyString(), anyString(), anyString(), anyString()))
                .thenAnswer(invocation -> {
                    beforeModelTurn.get().run();
                    CreateGenerationPlan plan = plan();
                    return new CreateSpecDefaults().fromRequest(
                            invocation.getArgument(0), plan, plan.slotGroups().getFirst(), "test");
                });
        CreateSpecService service = new CreateSpecService(
                serviceFactory, new CreateSpecNormalizer(), contexts);

        CreateSpecService.SpecResult result = service.generateManaged(
                "create-spec-task", "创建一个官网", plan());

        assertEquals("ai_spec", result.reason());
        assertEquals(1, context.used(GenerationBudgetKind.ROOT_MODEL_ATTEMPT));
        assertEquals(1, context.used(GenerationBudgetKind.MODEL_TURN));
        assertEquals(0, context.used(GenerationBudgetKind.PROVIDER_FAILOVER_ATTEMPT));
        verify(serviceFactory).createExecutionService(
                eq(Duration.ofSeconds(20)), any(Runnable.class), any(Runnable.class));
    }

    @Test
    void cancelledManagedSpecCallMustNotCreateProviderService() {
        GenerationExecutionContextService contexts =
                new GenerationExecutionContextService(new GenerationRuntimeProperties());
        GenerationExecutionContext context = contexts.start("create-spec-cancelled", 11L, 22L);
        context.cancel("user_requested");
        AiCreateSpecServiceFactory serviceFactory = mock(AiCreateSpecServiceFactory.class);
        CreateSpecService service = new CreateSpecService(
                serviceFactory, new CreateSpecNormalizer(), contexts);

        assertThrows(GenerationExecutionCancelledException.class,
                () -> service.generateManaged(
                        "create-spec-cancelled", "创建一个官网", plan()));

        assertEquals(0, context.used(GenerationBudgetKind.ROOT_MODEL_ATTEMPT));
        verify(serviceFactory, never()).createExecutionService(
                any(Duration.class), any(Runnable.class), any(Runnable.class));
    }

    @Test
    void exhaustedPreviewQualityWindowMustSkipModelWithoutConsumingBudget() {
        AiCreateSpecServiceFactory serviceFactory = mock(AiCreateSpecServiceFactory.class);
        GenerationExecutionContextService contexts = mock(GenerationExecutionContextService.class);
        GenerationExecutionContext context = mock(GenerationExecutionContext.class);
        GenerationRuntimeProperties properties = new GenerationRuntimeProperties();
        when(contexts.getByTaskId("create-spec-preview-cutoff"))
                .thenReturn(Optional.of(context));
        when(context.limits()).thenReturn(properties.toLimits());
        when(context.optionalFirstPreviewOperationTimeout(Duration.ofMinutes(4)))
                .thenReturn(Optional.empty());
        CreateSpecService service = new CreateSpecService(
                serviceFactory, new CreateSpecNormalizer(), contexts);

        CreateSpecService.SpecResult result = service.generateManaged(
                "create-spec-preview-cutoff", "创建一个官网", plan());

        assertEquals("local_spec_first_preview_budget_exhausted", result.reason());
        assertFalse(result.modelAttempted());
        verify(context, never()).consume(GenerationBudgetKind.ROOT_MODEL_ATTEMPT);
        verify(serviceFactory, never()).createExecutionService(
                any(Duration.class), any(Runnable.class), any(Runnable.class));
        verify(serviceFactory, never()).createService();
    }

    private CreateGenerationPlan plan() {
        SlotGroup group = new SlotGroup(
                "landing-slots",
                "vue-web-landing",
                "landing",
                List.of("landing_core_data"),
                0
        );
        return new CreateGenerationPlan(
                CodeGenTypeEnum.VUE_PROJECT,
                new CreateTemplateManifest("vue-web-landing", CodeGenTypeEnum.VUE_PROJECT, "landing"),
                List.of(),
                List.of(group),
                0.9,
                "test",
                "test",
                ""
        );
    }
}
