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
import com.rush.rushaicodemother.orchestration.runtime.model.GenerationSynchronousModelCallSupervisor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CreateSpecServiceTest {

    private static final GenerationSynchronousModelCallSupervisor MODEL_CALL_SUPERVISOR =
            new GenerationSynchronousModelCallSupervisor();

    @AfterAll
    static void closeModelCallSupervisor() {
        MODEL_CALL_SUPERVISOR.close();
    }

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
    void localBackendFallbackMustPreserveEveryExplicitlyRequestedEntity() {
        AiCreateSpecServiceFactory serviceFactory = mock(AiCreateSpecServiceFactory.class);
        when(serviceFactory.createService()).thenThrow(new IllegalStateException("provider unavailable"));
        CreateSpecService service = new CreateSpecService(serviceFactory, new CreateSpecNormalizer());
        CreateGenerationPlan plan = backendPlan();

        CreateSpecService.SpecResult result = service.generate(
                "做一个商品和订单 CRUD 后端",
                plan,
                plan.slotGroups().getFirst()
        );

        assertEquals(
                List.of("Product", "Order"),
                result.spec().entities().stream().map(CreateSpec.EntitySpec::name).toList()
        );
        assertEquals(
                List.of("products", "orders"),
                result.spec().database().tables().stream().map(CreateSpec.TableSpec::name).toList()
        );
    }

    @Test
    void frozenBackendCapabilitySlotsMustOverrideWeakerModelSpec() {
        AiCreateSpecServiceFactory serviceFactory = mock(AiCreateSpecServiceFactory.class);
        AiCreateSpecService model = mock(AiCreateSpecService.class);
        CreateGenerationPlan plan = backendCapabilityPlan();
        CreateSpec localSpec = new CreateSpecDefaults().fromRequest(
                "做一个支持搜索、分页、导入导出的商品后端",
                plan,
                plan.slotGroups().getFirst(),
                "test"
        );
        CreateSpec weakerModelSpec = new CreateSpec(
                localSpec.product(),
                localSpec.modules(),
                localSpec.entities(),
                localSpec.frontend(),
                new CreateSpec.Backend(
                        "rest", false, false, false, false, true,
                        List.of("createdAt", "updatedAt"), false, false,
                        List.of("required"), "standard_json", "product"
                ),
                localSpec.database(),
                localSpec.content(),
                localSpec.constraints()
        );
        when(serviceFactory.createService()).thenReturn(model);
        when(model.generateSpec(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(weakerModelSpec);
        CreateSpecService service = new CreateSpecService(serviceFactory, new CreateSpecNormalizer());

        CreateSpecService.SpecResult result = service.generate(
                "做一个支持搜索、分页、导入导出的商品后端",
                plan,
                plan.slotGroups().getFirst()
        );

        assertTrue(result.spec().backend().search());
        assertTrue(result.spec().backend().pagination());
        assertTrue(result.spec().backend().importExport());
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
                serviceFactory, new CreateSpecNormalizer(), contexts, MODEL_CALL_SUPERVISOR);

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
                serviceFactory, new CreateSpecNormalizer(), contexts, MODEL_CALL_SUPERVISOR);

        assertThrows(GenerationExecutionCancelledException.class,
                () -> service.generateManaged(
                        "create-spec-cancelled", "创建一个官网", plan()));

        assertEquals(0, context.used(GenerationBudgetKind.ROOT_MODEL_ATTEMPT));
        verify(serviceFactory, never()).createExecutionService(
                any(Duration.class), any(Runnable.class), any(Runnable.class));
    }

    @Test
    void cancellationDuringProviderCallMustReleaseCreateSpecWorkerPromptly() throws Exception {
        GenerationExecutionContextService contexts =
                new GenerationExecutionContextService(new GenerationRuntimeProperties());
        GenerationExecutionContext context = contexts.start("create-spec-inflight-cancel", 11L, 22L);
        AiCreateSpecServiceFactory serviceFactory = mock(AiCreateSpecServiceFactory.class);
        AiCreateSpecService model = mock(AiCreateSpecService.class);
        CountDownLatch providerEntered = new CountDownLatch(1);
        CountDownLatch releaseProvider = new CountDownLatch(1);
        when(serviceFactory.createExecutionService(
                any(Duration.class), any(Runnable.class), any(Runnable.class)))
                .thenReturn(model);
        when(model.generateSpec(anyString(), anyString(), anyString(), anyString()))
                .thenAnswer(invocation -> {
                    providerEntered.countDown();
                    awaitIgnoringInterruption(releaseProvider);
                    return new CreateSpecDefaults().fromRequest(
                            invocation.getArgument(0), plan(), plan().slotGroups().getFirst(), "test");
                });

        ExecutorService managedWorker = Executors.newSingleThreadExecutor();
        try (AnnotationConfigApplicationContext spring = new AnnotationConfigApplicationContext()) {
            spring.registerBean(AiCreateSpecServiceFactory.class, () -> serviceFactory);
            spring.registerBean(CreateSpecNormalizer.class, CreateSpecNormalizer::new);
            spring.registerBean(GenerationExecutionContextService.class, () -> contexts);
            spring.registerBean(GenerationSynchronousModelCallSupervisor.class,
                    GenerationSynchronousModelCallSupervisor::new);
            spring.register(CreateSpecService.class);
            spring.refresh();
            CreateSpecService service = spring.getBean(CreateSpecService.class);
            Future<Throwable> outcome = managedWorker.submit(() -> {
                try {
                    service.generateManaged("create-spec-inflight-cancel", "创建一个官网", plan());
                    return null;
                } catch (Throwable failure) {
                    return failure;
                }
            });

            assertTrue(providerEntered.await(1, TimeUnit.SECONDS));
            context.cancel("user_requested");

            Throwable failure = outcome.get(1, TimeUnit.SECONDS);
            assertInstanceOf(GenerationExecutionCancelledException.class, failure);
        } finally {
            releaseProvider.countDown();
            managedWorker.shutdownNow();
        }
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
                serviceFactory, new CreateSpecNormalizer(), contexts, MODEL_CALL_SUPERVISOR);

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

    private CreateGenerationPlan backendPlan() {
        SlotGroup group = new SlotGroup(
                "backend-slots",
                "go-sqlite-backend-basic",
                "backend",
                List.of("domain_contract", "module_model", "module_repository", "module_service",
                        "module_handler", "database_schema", "module_import", "server_wiring"),
                0
        );
        return new CreateGenerationPlan(
                CodeGenTypeEnum.BACKEND_PROJECT,
                new CreateTemplateManifest(
                        "go-sqlite-backend-basic",
                        CodeGenTypeEnum.BACKEND_PROJECT,
                        "backend"
                ),
                List.of(),
                List.of(group),
                0.9,
                "test",
                "test",
                ""
        );
    }

    private CreateGenerationPlan backendCapabilityPlan() {
        CreateGenerationPlan base = backendPlan();
        SlotGroup group = new SlotGroup(
                "backend-capability-slots",
                "go-sqlite-backend-basic",
                "backend",
                List.of("domain_contract", "module_model", "module_repository", "module_service",
                        "module_handler", "database_schema", "module_import", "server_wiring",
                        "module_search", "module_pagination", "module_import_export"),
                0
        );
        return new CreateGenerationPlan(
                base.codeGenType(),
                base.baseTemplate(),
                base.modules(),
                List.of(group),
                base.confidence(),
                base.reason(),
                base.plannerSource(),
                base.fallbackReason()
        );
    }

    private static void awaitIgnoringInterruption(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException ignored) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
