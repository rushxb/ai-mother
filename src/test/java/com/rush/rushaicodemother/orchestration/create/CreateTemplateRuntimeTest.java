package com.rush.rushaicodemother.orchestration.create;

import com.rush.rushaicodemother.ai.model.CreateSpec;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.monitor.GenerationPerformanceMonitorService;
import com.rush.rushaicodemother.monitor.MonitorContext;
import com.rush.rushaicodemother.monitor.MonitorContextHolder;
import com.rush.rushaicodemother.orchestration.GenerationSession;
import com.rush.rushaicodemother.orchestration.GenerationStoppedException;
import com.rush.rushaicodemother.orchestration.GenerationTaskRequest;
import com.rush.rushaicodemother.orchestration.artifact.PatchApplyResult;
import com.rush.rushaicodemother.orchestration.codegraph.StructuredSyntaxValidationService;
import com.rush.rushaicodemother.orchestration.create.recipe.CreateRecipeRendererTestFactory;
import com.rush.rushaicodemother.orchestration.fullstack.FullStackGenerationContext;
import com.rush.rushaicodemother.orchestration.fullstack.FullStackPortAllocator;
import com.rush.rushaicodemother.orchestration.patch.GenerationPatchApplyService;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContext;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationRuntimeProperties;
import com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskFenceGuard;
import com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskExecutorProperties;
import com.rush.rushaicodemother.orchestration.template.BackendProjectTemplateBootstrapService;
import com.rush.rushaicodemother.orchestration.template.SlotFillResult;
import com.rush.rushaicodemother.orchestration.template.VueProjectTemplateBootstrapService;
import com.rush.rushaicodemother.orchestration.template.bootstrap.BackendGenerationTemplateBootstrapAdapter;
import com.rush.rushaicodemother.orchestration.template.bootstrap.FullStackGenerationTemplateBootstrapAdapter;
import com.rush.rushaicodemother.orchestration.template.bootstrap.GenerationTemplateBootstrapAdapter;
import com.rush.rushaicodemother.orchestration.template.bootstrap.GenerationTemplateBootstrapRegistry;
import com.rush.rushaicodemother.orchestration.template.bootstrap.VueGenerationTemplateBootstrapAdapter;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspaceService;
import com.rush.rushaicodemother.security.workspace.GeneratedSqlSafetyPolicy;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CreateTemplateRuntimeTest {

    @Test
    void shouldGenerateCreateSpecInParallelWithTemplateBootstrap() throws Exception {
        Path projectRoot = Path.of("target/test-workspaces/create-template-runtime/parallel/vue_project_1")
                .toAbsolutePath()
                .normalize();
        CountDownLatch specStarted = new CountDownLatch(1);
        CountDownLatch allowSpecCompletion = new CountDownLatch(1);
        CreateSpecService createSpecService = mock(CreateSpecService.class);
        VueProjectTemplateBootstrapService vueBootstrapService = mock(VueProjectTemplateBootstrapService.class);
        GenerationPatchApplyService patchApplyService = mock(GenerationPatchApplyService.class);
        GenerationTaskExecutorProperties properties = new GenerationTaskExecutorProperties();
        properties.setMaxConcurrency(1);
        CreateSpecTaskExecutor createSpecTaskExecutor = new CreateSpecTaskExecutor(properties);
        GenerationSession session = mock(GenerationSession.class);
        when(session.taskId()).thenReturn("create-parallel-task");
        when(session.isActive()).thenReturn(true);
        when(createSpecService.generateManaged(anyString(), anyString(), any())).thenAnswer(ignored -> {
            MonitorContext monitorContext = MonitorContextHolder.getContext();
            assertEquals("create-parallel-task", monitorContext == null ? null : monitorContext.getTaskId());
            specStarted.countDown();
            assertTrue(allowSpecCompletion.await(5, TimeUnit.SECONDS), "模板 bootstrap 未与规格生成并行执行");
            return new CreateSpecService.SpecResult(true, fitnessSpec(), "ai_spec");
        });
        when(vueBootstrapService.bootstrapIfNecessary(anyLong(), any(CodeGenTypeEnum.class), anyString()))
                .thenAnswer(ignored -> {
                    assertTrue(specStarted.await(5, TimeUnit.SECONDS), "规格生成未在模板 bootstrap 前启动");
                    allowSpecCompletion.countDown();
                    return VueProjectTemplateBootstrapService.BootstrapResult.created(
                            "vue-web-landing", projectRoot.toString(), 1);
                });
        when(patchApplyService.applyWithoutChangePlan(anyLong(), anyString(), any(), any(), anyString()))
                .thenReturn(PatchApplyResult.applied(
                        1L, "create-parallel-task", projectRoot.toString(), 1,
                        List.of("src/data/landingData.ts")));
        CreateTemplateRuntime runtime = runtime(
                mock(BackendProjectTemplateBootstrapService.class),
                new CreatePatchMergeService(),
                preWriteValidationService(),
                createSpecService,
                CreateRecipeRendererTestFactory.create(),
                null,
                patchApplyService,
                mock(GenerationTaskFenceGuard.class),
                workspaceService(projectRoot, CodeGenTypeEnum.VUE_PROJECT),
                new LandingSlotFallbackRenderer(),
                vueBootstrapService,
                new GenerationPerformanceMonitorService(),
                createSpecTaskExecutor
        );

        try {
            SlotFillResult result = runtime.generate(
                    app(), request("做一个 FitPilot 健身房 SaaS 官网"), landingDataPlan(), session);

            assertEquals(1, result.filledSlotCount());
            assertTrue(result.patchOperations().getFirst().content().contains("FitPilot"));
            verify(vueBootstrapService).bootstrapIfNecessary(
                    1L, CodeGenTypeEnum.VUE_PROJECT, "做一个 FitPilot 健身房 SaaS 官网");
            verify(createSpecService).generateManaged(anyString(), anyString(), any());
        } finally {
            allowSpecCompletion.countDown();
            createSpecTaskExecutor.shutdown();
        }
    }

    @Test
    void shouldFallbackToSynchronousSpecAfterBootstrapWhenParallelCapacityIsFull() throws Exception {
        Path projectRoot = Path.of("target/test-workspaces/create-template-runtime/saturated/vue_project_1")
                .toAbsolutePath()
                .normalize();
        GenerationTaskExecutorProperties properties = new GenerationTaskExecutorProperties();
        properties.setMaxConcurrency(1);
        CreateSpecTaskExecutor createSpecTaskExecutor = new CreateSpecTaskExecutor(properties);
        CountDownLatch capacityHeld = new CountDownLatch(1);
        CountDownLatch releaseCapacity = new CountDownLatch(1);
        Future<?> capacityHolder = createSpecTaskExecutor.submit(null, () -> {
            capacityHeld.countDown();
            releaseCapacity.await();
            return null;
        });
        assertTrue(capacityHeld.await(5, TimeUnit.SECONDS));

        AtomicBoolean bootstrapCompleted = new AtomicBoolean(false);
        CreateSpecService createSpecService = mock(CreateSpecService.class);
        VueProjectTemplateBootstrapService vueBootstrapService = mock(VueProjectTemplateBootstrapService.class);
        GenerationPatchApplyService patchApplyService = mock(GenerationPatchApplyService.class);
        when(vueBootstrapService.bootstrapIfNecessary(anyLong(), any(CodeGenTypeEnum.class), anyString()))
                .thenAnswer(ignored -> {
                    bootstrapCompleted.set(true);
                    return VueProjectTemplateBootstrapService.BootstrapResult.created(
                            "vue-web-landing", projectRoot.toString(), 1);
                });
        when(createSpecService.generate(anyString(), any())).thenAnswer(ignored -> {
            assertTrue(bootstrapCompleted.get(), "执行器饱和时应在模板 bootstrap 后同步生成规格");
            return new CreateSpecService.SpecResult(true, fitnessSpec(), "ai_spec");
        });
        when(patchApplyService.applyWithoutChangePlan(anyLong(), anyString(), any(), any(), anyString()))
                .thenReturn(PatchApplyResult.applied(
                        1L, "task", projectRoot.toString(), 1, List.of("src/data/landingData.ts")));
        CreateTemplateRuntime runtime = runtime(
                mock(BackendProjectTemplateBootstrapService.class),
                new CreatePatchMergeService(),
                preWriteValidationService(),
                createSpecService,
                CreateRecipeRendererTestFactory.create(),
                null,
                patchApplyService,
                mock(GenerationTaskFenceGuard.class),
                workspaceService(projectRoot, CodeGenTypeEnum.VUE_PROJECT),
                new LandingSlotFallbackRenderer(),
                vueBootstrapService,
                new GenerationPerformanceMonitorService(),
                createSpecTaskExecutor
        );

        try {
            SlotFillResult result = runtime.generate(app(), request(), landingDataPlan());

            assertEquals(1, result.filledSlotCount());
            assertTrue(result.patchOperations().getFirst().content().contains("FitPilot"));
            verify(createSpecService).generate(anyString(), any());
        } finally {
            releaseCapacity.countDown();
            capacityHolder.get(5, TimeUnit.SECONDS);
            createSpecTaskExecutor.shutdown();
        }
    }

    @Test
    void shouldCancelParallelSpecWhenGenerationTaskIsCancelled() throws Exception {
        Path projectRoot = Path.of("target/test-workspaces/create-template-runtime/cancelled/vue_project_1")
                .toAbsolutePath()
                .normalize();
        GenerationTaskExecutorProperties properties = new GenerationTaskExecutorProperties();
        properties.setMaxConcurrency(1);
        CreateSpecTaskExecutor createSpecTaskExecutor = new CreateSpecTaskExecutor(properties);
        CountDownLatch specStarted = new CountDownLatch(1);
        CountDownLatch keepSpecBlocked = new CountDownLatch(1);
        CountDownLatch specInterrupted = new CountDownLatch(1);
        GenerationExecutionContext executionContext = new GenerationExecutionContext(
                "create-cancelled-task",
                1L,
                2L,
                Instant.now(),
                new GenerationRuntimeProperties().toLimits(),
                Clock.systemUTC()
        );
        GenerationSession session = new GenerationSession(null, executionContext);
        CreateSpecService createSpecService = mock(CreateSpecService.class);
        VueProjectTemplateBootstrapService vueBootstrapService = mock(VueProjectTemplateBootstrapService.class);
        when(createSpecService.generateManaged(anyString(), anyString(), any())).thenAnswer(ignored -> {
            specStarted.countDown();
            try {
                keepSpecBlocked.await();
                return new CreateSpecService.SpecResult(true, fitnessSpec(), "ai_spec");
            } catch (InterruptedException interrupted) {
                specInterrupted.countDown();
                Thread.currentThread().interrupt();
                throw new IllegalStateException("CREATE 规格模型调用已被中断", interrupted);
            }
        });
        when(vueBootstrapService.bootstrapIfNecessary(anyLong(), any(CodeGenTypeEnum.class), anyString()))
                .thenAnswer(ignored -> {
                    assertTrue(specStarted.await(5, TimeUnit.SECONDS));
                    executionContext.cancel("user_requested");
                    return VueProjectTemplateBootstrapService.BootstrapResult.created(
                            "vue-web-landing", projectRoot.toString(), 1);
                });
        CreateTemplateRuntime runtime = runtime(
                mock(BackendProjectTemplateBootstrapService.class),
                new CreatePatchMergeService(),
                preWriteValidationService(),
                createSpecService,
                CreateRecipeRendererTestFactory.create(),
                null,
                mock(GenerationPatchApplyService.class),
                mock(GenerationTaskFenceGuard.class),
                workspaceService(projectRoot, CodeGenTypeEnum.VUE_PROJECT),
                new LandingSlotFallbackRenderer(),
                vueBootstrapService,
                new GenerationPerformanceMonitorService(),
                createSpecTaskExecutor
        );

        try {
            assertThrows(GenerationStoppedException.class,
                    () -> runtime.generate(app(), request(), landingDataPlan(), session));
            assertTrue(specInterrupted.await(5, TimeUnit.SECONDS), "任务取消后规格子线程未被中断");
        } finally {
            keepSpecBlocked.countDown();
            createSpecTaskExecutor.shutdown();
        }
    }

    @Test
    void shouldCancelParallelSpecAndUseLocalSpecWhenPreviewReserveBegins() throws Exception {
        Path projectRoot = Path.of("target/test-workspaces/create-template-runtime/preview-cutoff/vue_project_1")
                .toAbsolutePath()
                .normalize();
        GenerationTaskExecutorProperties executorProperties = new GenerationTaskExecutorProperties();
        executorProperties.setMaxConcurrency(1);
        CreateSpecTaskExecutor createSpecTaskExecutor = new CreateSpecTaskExecutor(executorProperties);
        CountDownLatch specStarted = new CountDownLatch(1);
        CountDownLatch keepSpecBlocked = new CountDownLatch(1);
        CountDownLatch specInterrupted = new CountDownLatch(1);
        Instant startedAt = Instant.parse("2026-07-22T00:00:00Z");
        GenerationRuntimeProperties runtimeProperties = new GenerationRuntimeProperties();
        runtimeProperties.setTaskTimeout(Duration.ofSeconds(60));
        runtimeProperties.setModelCallTimeout(Duration.ofSeconds(20));
        runtimeProperties.setFirstPreviewCompletionReserve(Duration.ofSeconds(45));
        GenerationExecutionContext executionContext = new GenerationExecutionContext(
                "create-preview-cutoff-task",
                1L,
                2L,
                startedAt,
                runtimeProperties.toLimits(),
                Clock.fixed(startedAt.plusSeconds(16), ZoneOffset.UTC)
        );
        GenerationSession session = new GenerationSession(null, executionContext);
        CreateSpecService createSpecService = mock(CreateSpecService.class);
        VueProjectTemplateBootstrapService vueBootstrapService = mock(VueProjectTemplateBootstrapService.class);
        GenerationPatchApplyService patchApplyService = mock(GenerationPatchApplyService.class);
        when(createSpecService.generateManaged(anyString(), anyString(), any())).thenAnswer(ignored -> {
            specStarted.countDown();
            try {
                keepSpecBlocked.await();
                return new CreateSpecService.SpecResult(true, fitnessSpec(), "ai_spec");
            } catch (InterruptedException interrupted) {
                specInterrupted.countDown();
                Thread.currentThread().interrupt();
                throw new IllegalStateException("CREATE 规格模型调用已被首预览预留窗口中断", interrupted);
            }
        });
        when(createSpecService.generateLocal(anyString(), any(), anyString()))
                .thenReturn(new CreateSpecService.SpecResult(
                        true, fitnessSpec(), "local_spec_first_preview_wait_cutoff"));
        when(vueBootstrapService.bootstrapIfNecessary(anyLong(), any(CodeGenTypeEnum.class), anyString()))
                .thenAnswer(ignored -> {
                    assertTrue(specStarted.await(5, TimeUnit.SECONDS));
                    return VueProjectTemplateBootstrapService.BootstrapResult.created(
                            "vue-web-landing", projectRoot.toString(), 1);
                });
        when(patchApplyService.applyWithoutChangePlan(anyLong(), anyString(), any(), any(), anyString()))
                .thenReturn(PatchApplyResult.applied(
                        1L, "create-preview-cutoff-task", projectRoot.toString(), 1,
                        List.of("src/data/landingData.ts")));
        CreateTemplateRuntime runtime = runtime(
                mock(BackendProjectTemplateBootstrapService.class),
                new CreatePatchMergeService(),
                preWriteValidationService(),
                createSpecService,
                CreateRecipeRendererTestFactory.create(),
                null,
                patchApplyService,
                mock(GenerationTaskFenceGuard.class),
                workspaceService(projectRoot, CodeGenTypeEnum.VUE_PROJECT),
                new LandingSlotFallbackRenderer(),
                vueBootstrapService,
                new GenerationPerformanceMonitorService(),
                createSpecTaskExecutor
        );

        try {
            SlotFillResult result = runtime.generate(app(), request(), landingDataPlan(), session);

            assertEquals(1, result.filledSlotCount());
            Map<?, ?> telemetry = (Map<?, ?>) result.metadata().get("telemetry");
            assertEquals(0, telemetry.get("aiCallCount"));
            assertEquals(true, telemetry.get("degraded"));
            assertTrue(specInterrupted.await(5, TimeUnit.SECONDS), "首预览预留生效后规格线程未被中断");
            verify(createSpecService).generateLocal(
                    anyString(), any(), org.mockito.ArgumentMatchers.eq("local_spec_first_preview_wait_cutoff"));
        } finally {
            keepSpecBlocked.countDown();
            createSpecTaskExecutor.shutdown();
        }
    }

    @Test
    void shouldUseAiCreateSpecRecipeForLandingCreate() {
        Path workspaceRoot = Path.of("target/test-workspaces/create-template-runtime")
                .toAbsolutePath()
                .normalize();
        CreateSpecService createSpecService = mock(CreateSpecService.class);
        CreateRecipeRendererService recipeRendererService = CreateRecipeRendererTestFactory.create();
        GenerationPatchApplyService patchApplyService = mock(GenerationPatchApplyService.class);
        VueProjectTemplateBootstrapService vueBootstrapService = mock(VueProjectTemplateBootstrapService.class);
        Path projectRoot = workspaceRoot.resolve("vue_project_1").toAbsolutePath().normalize();
        when(vueBootstrapService.bootstrapIfNecessary(anyLong(), any(CodeGenTypeEnum.class), anyString()))
                .thenReturn(VueProjectTemplateBootstrapService.BootstrapResult.created(
                        "vue-web-landing",
                        projectRoot.toString(),
                        1
                ));
        when(createSpecService.generate(anyString(), any()))
                .thenReturn(new CreateSpecService.SpecResult(true, fitnessSpec(), ""));
        when(createSpecService.generateManaged(anyString(), anyString(), any()))
                .thenReturn(new CreateSpecService.SpecResult(true, fitnessSpec(), ""));
        when(patchApplyService.applyWithoutChangePlan(anyLong(), anyString(), any(), any(), anyString()))
                .thenReturn(PatchApplyResult.applied(1L, "task", projectRoot.toString(), 1, List.of("src/data/landingData.ts")));
        GenerationPerformanceMonitorService performanceMonitorService = new GenerationPerformanceMonitorService();
        performanceMonitorService.startTask(
                "create-task", 1L, 2L, "create", CodeGenTypeEnum.VUE_PROJECT.getValue());
        GenerationSession session = mock(GenerationSession.class);
        when(session.taskId()).thenReturn("create-task");

        CreateTemplateRuntime runtime = runtime(
                mock(BackendProjectTemplateBootstrapService.class),
                new CreatePatchMergeService(),
                preWriteValidationService(),
                createSpecService,
                recipeRendererService,
                null,
                patchApplyService,
                mock(GenerationTaskFenceGuard.class),
                workspaceService(projectRoot, CodeGenTypeEnum.VUE_PROJECT),
                new LandingSlotFallbackRenderer(),
                vueBootstrapService,
                performanceMonitorService
        );

        SlotFillResult result = runtime.generate(app(), request("做一个 FitPilot 健身房 SaaS 官网"), landingDataPlan());

        runtime.generate(app(), request(), landingDataPlan(), session);

        assertEquals(1, result.filledSlotCount());
        assertEquals(1, result.patchOperationCount());
        String content = result.patchOperations().getFirst().content();
        assertTrue(content.contains("FitPilot"));
        assertTrue(content.contains("私教排课"));
        assertTrue(content.contains("#2563eb"));
        Map<?, ?> telemetry = (Map<?, ?>) result.metadata().get("telemetry");
        assertEquals(1, telemetry.get("aiCallCount"));
        assertEquals(false, telemetry.get("degraded"));
        assertTrue(performanceMonitorService.getSummary(10).getRecentTasks().getFirst()
                .getFirstTokenLatencyMs() > 0);
        List<String> stages = performanceMonitorService.getSummary(10).getRecentTasks().getFirst()
                .getSpans().stream()
                .map(span -> span.getStage())
                .toList();
        assertTrue(stages.containsAll(List.of(
                "create_bootstrap",
                "create_spec_model",
                "create_recipe_render",
                "create_pre_write_validation",
                "create_patch_apply",
                "model_time_to_first_signal"
        )));
    }

    @Test
    void shouldUseAiCreateSpecRecipeForAdminCreate() {
        Path workspaceRoot = Path.of("target/test-workspaces/create-template-runtime")
                .toAbsolutePath()
                .normalize();
        CreateSpecService createSpecService = mock(CreateSpecService.class);
        CreateRecipeRendererService recipeRendererService = CreateRecipeRendererTestFactory.create();
        GenerationPatchApplyService patchApplyService = mock(GenerationPatchApplyService.class);
        VueProjectTemplateBootstrapService vueBootstrapService = mock(VueProjectTemplateBootstrapService.class);
        Path projectRoot = workspaceRoot.resolve("vue_project_1").toAbsolutePath().normalize();
        when(vueBootstrapService.bootstrapIfNecessary(anyLong(), any(CodeGenTypeEnum.class), anyString()))
                .thenReturn(VueProjectTemplateBootstrapService.BootstrapResult.created(
                        "vue-web-admin",
                        projectRoot.toString(),
                        1
                ));
        when(createSpecService.generate(anyString(), any()))
                .thenReturn(new CreateSpecService.SpecResult(true, fitnessSpec(), ""));
        when(patchApplyService.applyWithoutChangePlan(anyLong(), anyString(), any(), any(), anyString()))
                .thenReturn(PatchApplyResult.applied(1L, "task", projectRoot.toString(), 4, List.of("src/data/adminData.ts")));

        CreateTemplateRuntime runtime = runtime(
                mock(BackendProjectTemplateBootstrapService.class),
                new CreatePatchMergeService(),
                preWriteValidationService(),
                createSpecService,
                recipeRendererService,
                null,
                patchApplyService,
                mock(GenerationTaskFenceGuard.class),
                workspaceService(projectRoot, CodeGenTypeEnum.VUE_PROJECT),
                new LandingSlotFallbackRenderer(),
                vueBootstrapService
        );

        SlotFillResult result = runtime.generate(app(), request("做一个健身房课程管理后台"), adminPlan());

        assertTrue(result.patchOperationCount() >= 3);
        assertTrue(result.patchOperations().stream().anyMatch(operation -> operation.relativePath().equals("src/data/adminData.ts")
                && operation.content().contains("FitPilot")));
    }

    @Test
    void incompleteRecipeCoverageMustHandoffBeforeApplyingAnyPatch() {
        Path workspaceRoot = Path.of("target/test-workspaces/create-template-runtime/incomplete-coverage")
                .toAbsolutePath()
                .normalize();
        Path projectRoot = workspaceRoot.resolve("vue_project_1");
        CreateSpecService createSpecService = mock(CreateSpecService.class);
        GenerationPatchApplyService patchApplyService = mock(GenerationPatchApplyService.class);
        VueProjectTemplateBootstrapService vueBootstrapService = mock(VueProjectTemplateBootstrapService.class);
        when(vueBootstrapService.bootstrapIfNecessary(anyLong(), any(CodeGenTypeEnum.class), anyString()))
                .thenReturn(VueProjectTemplateBootstrapService.BootstrapResult.created(
                        "vue-web-admin", projectRoot.toString(), 1));
        when(createSpecService.generate(anyString(), any()))
                .thenReturn(new CreateSpecService.SpecResult(true, fitnessSpec(), "ai_spec"));
        CreateTemplateRuntime runtime = runtime(
                mock(BackendProjectTemplateBootstrapService.class),
                new CreatePatchMergeService(),
                preWriteValidationService(),
                createSpecService,
                CreateRecipeRendererTestFactory.create(),
                null,
                patchApplyService,
                mock(GenerationTaskFenceGuard.class),
                workspaceService(projectRoot, CodeGenTypeEnum.VUE_PROJECT),
                new LandingSlotFallbackRenderer(),
                vueBootstrapService
        );

        SlotFillResult result = runtime.generate(
                app(), request("做一个后台商品管理系统"), partialAdminPlan());

        assertTrue(result.fallback());
        assertTrue(result.patchOperations().isEmpty());
        assertEquals(List.of("form_modal"), result.skippedSlots());
        verify(patchApplyService, never()).applyWithoutChangePlan(
                anyLong(), anyString(), any(), any(), anyString());
    }

    @Test
    void unsupportedRequiredSlotMustHandoffWithoutApplyingPatch() {
        Path workspaceRoot = Path.of("target/test-workspaces/create-template-runtime")
                .toAbsolutePath()
                .normalize();
        CreateSpecService createSpecService = mock(CreateSpecService.class);
        GenerationPatchApplyService patchApplyService = mock(GenerationPatchApplyService.class);
        VueProjectTemplateBootstrapService vueBootstrapService = mock(VueProjectTemplateBootstrapService.class);
        Path projectRoot = workspaceRoot.resolve("vue_project_1").toAbsolutePath().normalize();
        when(vueBootstrapService.bootstrapIfNecessary(anyLong(), any(CodeGenTypeEnum.class), anyString()))
                .thenReturn(VueProjectTemplateBootstrapService.BootstrapResult.created(
                        "vue-web-landing",
                        projectRoot.toString(),
                        1
                ));
        CreateTemplateRuntime runtime = runtime(
                mock(BackendProjectTemplateBootstrapService.class),
                new CreatePatchMergeService(),
                preWriteValidationService(),
                createSpecService,
                CreateRecipeRendererTestFactory.create(),
                null,
                patchApplyService,
                mock(GenerationTaskFenceGuard.class),
                workspaceService(projectRoot, CodeGenTypeEnum.VUE_PROJECT),
                new LandingSlotFallbackRenderer(),
                vueBootstrapService
        );
        when(createSpecService.generate(anyString(), any()))
                .thenReturn(new CreateSpecService.SpecResult(true, fitnessSpec(), ""));

        SlotFillResult result = runtime.generate(app(), request(), plan());

        assertTrue(result.patchOperations().isEmpty());
        Map<?, ?> telemetry = (Map<?, ?>) result.metadata().get("telemetry");
        assertEquals(true, telemetry.get("fallback"));
        assertEquals(true, telemetry.get("degraded"));
        verify(patchApplyService, never()).applyWithoutChangePlan(anyLong(), anyString(), any(), any(), anyString());
    }

    @Test
    void shouldCoalesceSlotGroupsByTemplateBeforeRenderingRecipe() {
        Path workspaceRoot = Path.of("target/test-workspaces/create-template-runtime")
                .toAbsolutePath()
                .normalize();
        CreateSpecService createSpecService = mock(CreateSpecService.class);
        GenerationPatchApplyService patchApplyService = mock(GenerationPatchApplyService.class);
        VueProjectTemplateBootstrapService vueBootstrapService = mock(VueProjectTemplateBootstrapService.class);
        Path projectRoot = workspaceRoot.resolve("vue_project_1").toAbsolutePath().normalize();
        when(vueBootstrapService.bootstrapIfNecessary(anyLong(), any(CodeGenTypeEnum.class), anyString()))
                .thenReturn(VueProjectTemplateBootstrapService.BootstrapResult.created(
                        "vue-web-landing",
                        projectRoot.toString(),
                        1
                ));
        CreateTemplateRuntime runtime = runtime(
                mock(BackendProjectTemplateBootstrapService.class),
                new CreatePatchMergeService(),
                preWriteValidationService(),
                createSpecService,
                CreateRecipeRendererTestFactory.create(),
                null,
                patchApplyService,
                mock(GenerationTaskFenceGuard.class),
                workspaceService(projectRoot, CodeGenTypeEnum.VUE_PROJECT),
                new LandingSlotFallbackRenderer(),
                vueBootstrapService
        );
        when(createSpecService.generate(anyString(), any()))
                .thenReturn(new CreateSpecService.SpecResult(true, fitnessSpec(), ""));
        when(patchApplyService.applyWithoutChangePlan(anyLong(), anyString(), any(), any(), anyString()))
                .thenReturn(PatchApplyResult.applied(1L, "task", projectRoot.toString(), 1, List.of("src/data/landingData.ts")));

        SlotFillResult result = runtime.generate(app(), request(), multiGroupPlan());

        assertEquals(1, result.filledSlotCount());
        Map<?, ?> telemetry = (Map<?, ?>) result.metadata().get("telemetry");
        assertEquals(1, telemetry.get("slotGroupCount"));
        assertEquals(1, telemetry.get("aiCallCount"));
        verify(createSpecService).generate(anyString(), any());
    }

    @Test
    void shouldGenerateOneCreateSpecForFullStackAndReuseAcrossGroups() {
        Path workspaceRoot = Path.of("target/test-workspaces/create-template-runtime/full-stack")
                .toAbsolutePath()
                .normalize();
        CreateSpecService createSpecService = mock(CreateSpecService.class);
        GenerationPatchApplyService patchApplyService = mock(GenerationPatchApplyService.class);
        VueProjectTemplateBootstrapService vueBootstrapService = mock(VueProjectTemplateBootstrapService.class);
        BackendProjectTemplateBootstrapService backendBootstrapService = mock(BackendProjectTemplateBootstrapService.class);
        FullStackPortAllocator portAllocator = mock(FullStackPortAllocator.class);
        when(portAllocator.allocate(any(GenerationWorkspace.class)))
                .thenReturn(FullStackGenerationContext.create(
                        17001,
                        18001,
                        workspace(workspaceRoot, CodeGenTypeEnum.FULL_STACK_PROJECT)
                ));
        when(vueBootstrapService.bootstrapIfNecessary(anyLong(), any(CodeGenTypeEnum.class), anyString()))
                .thenReturn(VueProjectTemplateBootstrapService.BootstrapResult.created(
                        "vue-web-admin",
                        workspaceRoot.resolve("frontend").toString(),
                        1
                ));
        when(backendBootstrapService.bootstrapIfNecessary(anyLong(), any(CodeGenTypeEnum.class)))
                .thenReturn(BackendProjectTemplateBootstrapService.BootstrapResult.created(
                        "go-sqlite-backend-basic",
                        workspaceRoot.resolve("backend").toString(),
                        1
                ));
        when(createSpecService.generate(anyString(), any()))
                .thenReturn(new CreateSpecService.SpecResult(true, fitnessSpec(), ""));
        when(patchApplyService.applyWithoutChangePlan(anyLong(), anyString(), any(), any(), anyString()))
                .thenReturn(PatchApplyResult.applied(1L, "task", workspaceRoot.toString(), 10,
                        List.of("frontend/src/data/adminData.ts", "backend/internal/modules/course/model.go")));

        CreateTemplateRuntime runtime = runtime(
                backendBootstrapService,
                new CreatePatchMergeService(),
                preWriteValidationService(),
                createSpecService,
                CreateRecipeRendererTestFactory.create(),
                portAllocator,
                patchApplyService,
                mock(GenerationTaskFenceGuard.class),
                workspaceService(workspaceRoot, CodeGenTypeEnum.FULL_STACK_PROJECT),
                new LandingSlotFallbackRenderer(),
                vueBootstrapService
        );

        SlotFillResult result = runtime.generate(app(CodeGenTypeEnum.FULL_STACK_PROJECT),
                request("做一个健身房全栈后台", CodeGenTypeEnum.FULL_STACK_PROJECT), fullStackPlan());

        assertEquals(1, ((Map<?, ?>) result.metadata().get("telemetry")).get("aiCallCount"));
        assertTrue(result.patchOperations().stream().anyMatch(operation -> operation.relativePath().startsWith("frontend/")));
        assertTrue(result.patchOperations().stream().anyMatch(operation -> operation.relativePath().startsWith("backend/")));
        verify(createSpecService, times(1)).generate(anyString(), any());
        verify(portAllocator).allocate(any(GenerationWorkspace.class));
        verify(vueBootstrapService).bootstrapIfNecessary(
                1L,
                CodeGenTypeEnum.FULL_STACK_PROJECT,
                "做一个健身房全栈后台"
        );
        verify(backendBootstrapService).bootstrapIfNecessary(1L, CodeGenTypeEnum.FULL_STACK_PROJECT);
    }

    @Test
    void shouldUseLocalSpecRecipeWhenAiSpecTimesOut() {
        Path workspaceRoot = Path.of("target/test-workspaces/create-template-runtime")
                .toAbsolutePath()
                .normalize();
        CreateSpecService createSpecService = new CreateSpecService(
                mock(com.rush.rushaicodemother.ai.AiCreateSpecServiceFactory.class),
                new CreateSpecNormalizer()
        );
        GenerationPatchApplyService patchApplyService = mock(GenerationPatchApplyService.class);
        VueProjectTemplateBootstrapService vueBootstrapService = mock(VueProjectTemplateBootstrapService.class);
        Path projectRoot = workspaceRoot.resolve("vue_project_1").toAbsolutePath().normalize();
        when(vueBootstrapService.bootstrapIfNecessary(anyLong(), any(CodeGenTypeEnum.class), anyString()))
                .thenReturn(VueProjectTemplateBootstrapService.BootstrapResult.created(
                        "vue-web-landing",
                        projectRoot.toString(),
                        1
                ));
        CreateTemplateRuntime runtime = runtime(
                mock(BackendProjectTemplateBootstrapService.class),
                new CreatePatchMergeService(),
                preWriteValidationService(),
                createSpecService,
                CreateRecipeRendererTestFactory.create(),
                null,
                patchApplyService,
                mock(GenerationTaskFenceGuard.class),
                workspaceService(projectRoot, CodeGenTypeEnum.VUE_PROJECT),
                new LandingSlotFallbackRenderer(),
                vueBootstrapService
        );
        when(patchApplyService.applyWithoutChangePlan(anyLong(), anyString(), any(), any(), anyString()))
                .thenReturn(PatchApplyResult.applied(1L, "task", projectRoot.toString(), 1, List.of("src/data/landingData.ts")));

        SlotFillResult result = runtime.generate(app(), request(), landingDataPlan());

        assertEquals(1, result.filledSlotCount());
        assertEquals(1, result.patchOperationCount());
        Map<?, ?> telemetry = (Map<?, ?>) result.metadata().get("telemetry");
        assertEquals(false, telemetry.get("fallback"));
        assertEquals(true, telemetry.get("degraded"));
        assertEquals(1, telemetry.get("aiCallCount"));
        verify(patchApplyService, times(1)).applyWithoutChangePlan(anyLong(), anyString(), any(), any(), anyString());
    }

    private CreateTemplateRuntime runtime(
            BackendProjectTemplateBootstrapService backendBootstrapService,
            CreatePatchMergeService patchMergeService,
            CreatePreWriteValidationService preWriteValidationService,
            CreateSpecService specService,
            CreateRecipeRendererService recipeRendererService,
            FullStackPortAllocator portAllocator,
            GenerationPatchApplyService patchApplyService,
            GenerationTaskFenceGuard fenceGuard,
            GenerationWorkspaceService workspaceService,
            LandingSlotFallbackRenderer fallbackRenderer,
            VueProjectTemplateBootstrapService vueBootstrapService
    ) {
        return runtime(
                backendBootstrapService,
                patchMergeService,
                preWriteValidationService,
                specService,
                recipeRendererService,
                portAllocator,
                patchApplyService,
                fenceGuard,
                workspaceService,
                fallbackRenderer,
                vueBootstrapService,
                new GenerationPerformanceMonitorService(),
                new CreateSpecTaskExecutor()
        );
    }

    private CreateTemplateRuntime runtime(
            BackendProjectTemplateBootstrapService backendBootstrapService,
            CreatePatchMergeService patchMergeService,
            CreatePreWriteValidationService preWriteValidationService,
            CreateSpecService specService,
            CreateRecipeRendererService recipeRendererService,
            FullStackPortAllocator portAllocator,
            GenerationPatchApplyService patchApplyService,
            GenerationTaskFenceGuard fenceGuard,
            GenerationWorkspaceService workspaceService,
            LandingSlotFallbackRenderer fallbackRenderer,
            VueProjectTemplateBootstrapService vueBootstrapService,
            GenerationPerformanceMonitorService performanceMonitorService
    ) {
        return runtime(
                backendBootstrapService,
                patchMergeService,
                preWriteValidationService,
                specService,
                recipeRendererService,
                portAllocator,
                patchApplyService,
                fenceGuard,
                workspaceService,
                fallbackRenderer,
                vueBootstrapService,
                performanceMonitorService,
                new CreateSpecTaskExecutor()
        );
    }

    private CreateTemplateRuntime runtime(
            BackendProjectTemplateBootstrapService backendBootstrapService,
            CreatePatchMergeService patchMergeService,
            CreatePreWriteValidationService preWriteValidationService,
            CreateSpecService specService,
            CreateRecipeRendererService recipeRendererService,
            FullStackPortAllocator portAllocator,
            GenerationPatchApplyService patchApplyService,
            GenerationTaskFenceGuard fenceGuard,
            GenerationWorkspaceService workspaceService,
            LandingSlotFallbackRenderer fallbackRenderer,
            VueProjectTemplateBootstrapService vueBootstrapService,
            GenerationPerformanceMonitorService performanceMonitorService,
            CreateSpecTaskExecutor specTaskExecutor
    ) {
        return new CreateTemplateRuntime(
                templateBootstrapRegistry(
                        workspaceService,
                        vueBootstrapService,
                        backendBootstrapService,
                        portAllocator
                ),
                patchMergeService,
                preWriteValidationService,
                specService,
                recipeRendererService,
                patchApplyService,
                fenceGuard,
                fallbackRenderer,
                performanceMonitorService,
                specTaskExecutor
        );
    }

    private GenerationTemplateBootstrapRegistry templateBootstrapRegistry(
            GenerationWorkspaceService workspaceService,
            VueProjectTemplateBootstrapService vueBootstrapService,
            BackendProjectTemplateBootstrapService backendBootstrapService,
            FullStackPortAllocator portAllocator
    ) {
        List<GenerationTemplateBootstrapAdapter> adapters = new ArrayList<>();
        adapters.add(new VueGenerationTemplateBootstrapAdapter(vueBootstrapService));
        adapters.add(new BackendGenerationTemplateBootstrapAdapter(backendBootstrapService));
        if (portAllocator != null) {
            adapters.add(new FullStackGenerationTemplateBootstrapAdapter(
                    vueBootstrapService,
                    backendBootstrapService,
                    portAllocator
            ));
        }
        return new GenerationTemplateBootstrapRegistry(workspaceService, adapters);
    }

    private GenerationWorkspaceService workspaceService(Path root, CodeGenTypeEnum codeGenType) {
        GenerationWorkspaceService service = mock(GenerationWorkspaceService.class);
        when(service.resolve(1L, codeGenType)).thenReturn(workspace(root, codeGenType));
        return service;
    }

    private GenerationWorkspace workspace(Path root, CodeGenTypeEnum codeGenType) {
        Path canonicalRoot = root.toAbsolutePath().normalize();
        Path frontendRoot = codeGenType == CodeGenTypeEnum.FULL_STACK_PROJECT
                ? canonicalRoot.resolve("frontend")
                : canonicalRoot;
        Path backendRoot = switch (codeGenType) {
            case FULL_STACK_PROJECT -> canonicalRoot.resolve("backend");
            case BACKEND_PROJECT -> canonicalRoot;
            default -> null;
        };
        return new GenerationWorkspace(
                1L,
                codeGenType,
                canonicalRoot,
                canonicalRoot,
                true,
                frontendRoot,
                backendRoot,
                GenerationWorkspaceService.HIDDEN_FILE_NAMES,
                GenerationWorkspaceService.EDITABLE_EXTENSIONS
        );
    }

    private App app() {
        return app(CodeGenTypeEnum.VUE_PROJECT);
    }

    private App app(CodeGenTypeEnum codeGenType) {
        App app = new App();
        app.setId(1L);
        app.setCodeGenType(codeGenType.getValue());
        return app;
    }

    private GenerationTaskRequest request() {
        return request("做一个企业官网");
    }

    private GenerationTaskRequest request(String message) {
        User user = new User();
        user.setId(2L);
        return new GenerationTaskRequest(app(), message, user);
    }

    private GenerationTaskRequest request(String message, CodeGenTypeEnum codeGenType) {
        User user = new User();
        user.setId(2L);
        return new GenerationTaskRequest(app(codeGenType), message, user);
    }

    private CreateSpec fitnessSpec() {
        return new CreateSpec(
                new CreateSpec.Product("landing", "fitness_saas", "FitPilot", "健身房运营人员", "提升门店运营效率"),
                List.of(new CreateSpec.ModuleSpec("course_crud", "课程管理", List.of("table", "form"))),
                List.of(new CreateSpec.EntitySpec("Course", "课程", List.of(
                        new CreateSpec.FieldSpec("title", "string", "课程名称", true, List.of()),
                        new CreateSpec.FieldSpec("coach", "string", "教练", true, List.of()),
                        new CreateSpec.FieldSpec("price", "decimal", "价格", false, List.of()),
                        new CreateSpec.FieldSpec("status", "enum", "状态", false, List.of("上架", "下架")),
                        new CreateSpec.FieldSpec("capacity", "integer", "容量", false, List.of())
                ), List.of(), List.of("list", "create", "update", "delete"))),
                new CreateSpec.Frontend(
                        "landing_scroll",
                        List.of("专业", "运营中台"),
                        "compact",
                        List.of("metric_cards", "data_table"),
                        List.of("筛选", "分页"),
                        List.of("指标卡", "趋势图"),
                        List.of("亮点", "案例", "流程", "价格", "FAQ"),
                        new CreateSpec.Theme("#2563eb", "#f97316", "#f8fafc", "8px", "light")
                ),
                new CreateSpec.Backend("rest", false, true, true, true, true,
                        List.of("createdAt", "updatedAt"), false, true, List.of("required"),
                        "standard_json", "course"),
                new CreateSpec.Database(List.of(), List.of("title", "status"), true, "append_sql_schema"),
                new CreateSpec.Content(
                        "professional energetic",
                        "健身房运营数据",
                        List.of("亮点", "案例", "流程", "价格", "FAQ"),
                        List.of("landing", "pricing", "faq"),
                        new CreateSpec.Landing(
                                "让健身房运营更轻盈",
                                "用课程排班、会员跟进和经营看板，把门店运营变成可持续增长。",
                                "预约演示",
                                "查看方案",
                                List.of("亮点", "案例", "流程", "价格", "FAQ"),
                        List.of(
                                new CreateSpec.Stat("42%", "私教转化提升"),
                                new CreateSpec.Stat("12h", "每周排课节省"),
                                new CreateSpec.Stat("180+", "门店使用")
                        ),
                        List.of(
                                new CreateSpec.TextBlock("私教排课", "自动整理教练档期、课程容量和会员预约状态。"),
                                new CreateSpec.TextBlock("会员跟进", "把体验课、续费提醒和沉睡会员唤醒放到同一张运营清单。"),
                                new CreateSpec.TextBlock("经营看板", "实时查看课程满班率、私教转化和门店收入趋势。"),
                                new CreateSpec.TextBlock("多门店协同", "统一管理不同门店的课程、教练和会员数据。")
                        ),
                        List.of(
                                new CreateSpec.TextBlock("精品健身工作室", "上线后体验课转私教率提升 42%。"),
                                new CreateSpec.TextBlock("连锁瑜伽门店", "排课沟通时间每周减少 12 小时。"),
                                new CreateSpec.TextBlock("综合运动中心", "用统一看板追踪课程收入和会员留存。")
                        ),
                        List.of("运营诊断", "数据导入", "门店上线", "增长复盘"),
                        List.of(
                                new CreateSpec.Plan("单店版", "¥1,999/月", "适合单门店快速数字化。", List.of("课程排班", "会员管理", "基础看板")),
                                new CreateSpec.Plan("连锁版", "¥6,999/月", "适合多门店统一运营。", List.of("多门店管理", "教练绩效", "转化漏斗")),
                                new CreateSpec.Plan("定制版", "按需报价", "适合复杂系统集成。", List.of("私有部署", "数据对接", "专属支持"))
                        ),
                        List.of(
                                new CreateSpec.Faq("可以导入现有会员吗？", "可以，首次上线会协助整理会员、课程和教练数据。"),
                                new CreateSpec.Faq("支持多门店吗？", "支持按门店、角色和区域查看不同运营数据。"),
                                new CreateSpec.Faq("教练能单独使用吗？", "可以为教练配置移动端排课和会员跟进视图。"),
                                new CreateSpec.Faq("多久可以上线？", "标准门店通常 3-5 个工作日完成初始化。")
                        ),
                                new CreateSpec.Contact("hello@fitpilot.example", "400-800-2026", "线上演示可预约")
                        )
                ),
                new CreateSpec.Constraints(true, List.of("package.json", "go.mod"),
                        List.of("no_script_html", "no_secret"), 4, 8)
        );
    }

    private CreateGenerationPlan plan() {
        return new CreateGenerationPlan(
                CodeGenTypeEnum.VUE_PROJECT,
                new CreateTemplateManifest("vue-web-landing", CodeGenTypeEnum.VUE_PROJECT, "landing"),
                List.of(),
                List.of(new SlotGroup("hero", "vue-web-landing", "base", List.of("landing_data"), 1)),
                0.9,
                "test",
                "test",
                ""
        );
    }

    private CreatePreWriteValidationService preWriteValidationService() {
        return new CreatePreWriteValidationService(
                new StructuredSyntaxValidationService(),
                new GeneratedSqlSafetyPolicy()
        );
    }

    private CreateGenerationPlan multiGroupPlan() {
        return new CreateGenerationPlan(
                CodeGenTypeEnum.VUE_PROJECT,
                new CreateTemplateManifest("vue-web-landing", CodeGenTypeEnum.VUE_PROJECT, "landing"),
                List.of(
                        new FeatureModuleManifest("landing-page", "Landing", "vue-web-landing", List.of("landing_core_data"), ""),
                        new FeatureModuleManifest("landing-contact", "Contact", "vue-web-landing", List.of("landing_core_data"), "")
                ),
                List.of(
                        new SlotGroup("landing-page-slots", "vue-web-landing", "landing-page", List.of("landing_core_data"), 0),
                        new SlotGroup("landing-contact-slots", "vue-web-landing", "landing-contact", List.of("landing_core_data"), 1)
                ),
                0.9,
                "test",
                "test",
                ""
        );
    }

    private CreateGenerationPlan landingDataPlan() {
        return new CreateGenerationPlan(
                CodeGenTypeEnum.VUE_PROJECT,
                new CreateTemplateManifest("vue-web-landing", CodeGenTypeEnum.VUE_PROJECT, "landing"),
                List.of(new FeatureModuleManifest("landing-page", "Landing", "vue-web-landing",
                        List.of("landing_core_data"), "")),
                List.of(new SlotGroup("landing-page-slots", "vue-web-landing", "landing-page",
                        List.of("landing_core_data"), 0)),
                0.9,
                "test",
                "test",
                ""
        );
    }

    private CreateGenerationPlan adminPlan() {
        return new CreateGenerationPlan(
                CodeGenTypeEnum.VUE_PROJECT,
                new CreateTemplateManifest("vue-web-admin", CodeGenTypeEnum.VUE_PROJECT, "admin"),
                List.of(new FeatureModuleManifest("admin-dashboard", "Admin", "vue-web-admin",
                        List.of("dashboard_content", "mock_data", "table_columns", "sidebar_menu", "statistics_cards"), "")),
                List.of(new SlotGroup("admin-dashboard-slots", "vue-web-admin", "admin-dashboard",
                        List.of("dashboard_content", "mock_data", "table_columns", "sidebar_menu", "statistics_cards"), 0)),
                0.9,
                "test",
                "test",
                ""
        );
    }

    private CreateGenerationPlan partialAdminPlan() {
        return new CreateGenerationPlan(
                CodeGenTypeEnum.VUE_PROJECT,
                new CreateTemplateManifest("vue-web-admin", CodeGenTypeEnum.VUE_PROJECT, "admin"),
                List.of(new FeatureModuleManifest(
                        "admin-products",
                        "商品管理",
                        "vue-web-admin",
                        List.of("table_columns", "form_modal"),
                        "商品管理需要表格与编辑表单")),
                List.of(new SlotGroup(
                        "admin-products-slots",
                        "vue-web-admin",
                        "admin-products",
                        List.of("table_columns", "form_modal"),
                        0)),
                0.9,
                "test",
                "test",
                ""
        );
    }

    private CreateGenerationPlan fullStackPlan() {
        return new CreateGenerationPlan(
                CodeGenTypeEnum.FULL_STACK_PROJECT,
                new CreateTemplateManifest("full-stack-basic", CodeGenTypeEnum.FULL_STACK_PROJECT, "full stack"),
                List.of(
                        new FeatureModuleManifest("admin-dashboard", "Admin", "vue-web-admin",
                                List.of("dashboard_content", "mock_data", "table_columns", "sidebar_menu", "statistics_cards"), ""),
                        new FeatureModuleManifest("backend-crud", "Backend", "go-sqlite-backend-basic",
                                List.of("domain_contract", "module_model", "module_repository", "module_service",
                                        "module_handler", "database_schema", "module_import", "server_wiring"), "")
                ),
                List.of(
                        new SlotGroup("admin-dashboard-slots", "vue-web-admin", "admin-dashboard",
                                List.of("dashboard_content", "mock_data", "table_columns", "sidebar_menu", "statistics_cards"), 0),
                        new SlotGroup("backend-crud-slots", "go-sqlite-backend-basic", "backend-crud",
                                List.of("domain_contract", "module_model", "module_repository", "module_service",
                                        "module_handler", "database_schema", "module_import", "server_wiring"), 1)
                ),
                0.9,
                "test",
                "test",
                ""
        );
    }
}
