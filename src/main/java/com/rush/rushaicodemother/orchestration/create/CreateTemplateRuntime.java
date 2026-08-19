package com.rush.rushaicodemother.orchestration.create;

import cn.hutool.core.util.StrUtil;
import com.rush.rushaicodemother.core.handler.GenerationStreamEvent;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.monitor.GenerationPerformanceMonitorService;
import com.rush.rushaicodemother.monitor.MonitorContext;
import com.rush.rushaicodemother.monitor.MonitorContextHolder;
import com.rush.rushaicodemother.monitor.span.GenerationSpanCategory;
import com.rush.rushaicodemother.orchestration.GenerationSession;
import com.rush.rushaicodemother.orchestration.GenerationTaskRequest;
import com.rush.rushaicodemother.orchestration.artifact.PatchApplyResult;
import com.rush.rushaicodemother.orchestration.create.recipe.RecipeRenderResult;
import com.rush.rushaicodemother.orchestration.patch.GenerationPatchApplyService;
import com.rush.rushaicodemother.orchestration.patch.PatchOperation;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContext;
import com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskFenceGuard;
import com.rush.rushaicodemother.orchestration.template.SlotFillResult;
import com.rush.rushaicodemother.orchestration.template.bootstrap.GenerationTemplateBootstrapRegistry;
import com.rush.rushaicodemother.orchestration.template.bootstrap.GenerationTemplateBootstrapResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Service
public class CreateTemplateRuntime {

    private static final String BACKEND_TEMPLATE = "go-sqlite-backend-basic";
    private static final Duration SPEC_WAIT_POLL_INTERVAL = Duration.ofMillis(100);

    private final GenerationTemplateBootstrapRegistry templateBootstrapRegistry;
    private final CreatePatchMergeService createPatchMergeService;
    private final CreatePreWriteValidationService createPreWriteValidationService;
    private final CreateSpecService createSpecService;
    private final CreateSpecTaskExecutor createSpecTaskExecutor;
    private final CreateRecipeRendererService createRecipeRendererService;
    private final GenerationPatchApplyService generationPatchApplyService;
    private final GenerationPerformanceMonitorService generationPerformanceMonitorService;
    private final GenerationTaskFenceGuard generationTaskFenceGuard;
    private final LandingSlotFallbackRenderer landingSlotFallbackRenderer;

    /** 测试与轻量调用使用的构造器；监控和规格执行器采用默认实现。 */
    public CreateTemplateRuntime(GenerationTemplateBootstrapRegistry templateBootstrapRegistry,
                                 CreatePatchMergeService createPatchMergeService,
                                 CreatePreWriteValidationService createPreWriteValidationService,
                                 CreateSpecService createSpecService,
                                 CreateRecipeRendererService createRecipeRendererService,
                                 GenerationPatchApplyService generationPatchApplyService,
                                 GenerationTaskFenceGuard generationTaskFenceGuard,
                                 LandingSlotFallbackRenderer landingSlotFallbackRenderer) {
        this(
                templateBootstrapRegistry,
                createPatchMergeService,
                createPreWriteValidationService,
                createSpecService,
                createRecipeRendererService,
                generationPatchApplyService,
                generationTaskFenceGuard,
                landingSlotFallbackRenderer,
                new GenerationPerformanceMonitorService(),
                new CreateSpecTaskExecutor()
        );
    }

    /** 允许测试注入性能监控，规格执行器仍使用独立默认实例。 */
    public CreateTemplateRuntime(GenerationTemplateBootstrapRegistry templateBootstrapRegistry,
                                 CreatePatchMergeService createPatchMergeService,
                                 CreatePreWriteValidationService createPreWriteValidationService,
                                 CreateSpecService createSpecService,
                                 CreateRecipeRendererService createRecipeRendererService,
                                 GenerationPatchApplyService generationPatchApplyService,
                                 GenerationTaskFenceGuard generationTaskFenceGuard,
                                 LandingSlotFallbackRenderer landingSlotFallbackRenderer,
                                 GenerationPerformanceMonitorService generationPerformanceMonitorService) {
        this(
                templateBootstrapRegistry,
                createPatchMergeService,
                createPreWriteValidationService,
                createSpecService,
                createRecipeRendererService,
                generationPatchApplyService,
                generationTaskFenceGuard,
                landingSlotFallbackRenderer,
                generationPerformanceMonitorService,
                new CreateSpecTaskExecutor()
        );
    }

    /** 生产构造器；模板类型差异只允许从共享 registry 注入。 */
    @Autowired
    public CreateTemplateRuntime(GenerationTemplateBootstrapRegistry templateBootstrapRegistry,
                                 CreatePatchMergeService createPatchMergeService,
                                 CreatePreWriteValidationService createPreWriteValidationService,
                                 CreateSpecService createSpecService,
                                 CreateRecipeRendererService createRecipeRendererService,
                                 GenerationPatchApplyService generationPatchApplyService,
                                 GenerationTaskFenceGuard generationTaskFenceGuard,
                                 LandingSlotFallbackRenderer landingSlotFallbackRenderer,
                                 GenerationPerformanceMonitorService generationPerformanceMonitorService,
                                 CreateSpecTaskExecutor createSpecTaskExecutor) {
        this.templateBootstrapRegistry = Objects.requireNonNull(
                templateBootstrapRegistry, "模板初始化 registry 不能为空");
        this.createPatchMergeService = createPatchMergeService;
        this.createPreWriteValidationService = createPreWriteValidationService;
        this.createSpecService = createSpecService;
        this.createSpecTaskExecutor = createSpecTaskExecutor;
        this.createRecipeRendererService = createRecipeRendererService;
        this.generationPatchApplyService = generationPatchApplyService;
        this.generationPerformanceMonitorService = generationPerformanceMonitorService;
        this.generationTaskFenceGuard = generationTaskFenceGuard;
        this.landingSlotFallbackRenderer = landingSlotFallbackRenderer;
    }

    public SlotFillResult generate(App app, GenerationTaskRequest request, CreateGenerationPlan plan) {
        return generate(app, request, plan, null);
    }

    /**
 * 根据输入生成创建模板运行时。
 *
 * @param app 应用
 * @param request 请求参数
 * @param plan 计划
 * @param session 会话
 * @return 创建模板运行时
 */
    public SlotFillResult generate(App app, GenerationTaskRequest request, CreateGenerationPlan plan, GenerationSession session) {
        // 先处理前置条件和快速返回分支，避免无效输入进入核心流程。
        if (app == null || request == null || plan == null || plan.slotGroups().isEmpty()) {
            return null;
        }
        if (session != null && session.taskId() != null) {
            generationTaskFenceGuard.assertCurrent(session.taskId());
        }
        CreateSpecTask createSpecTask = submitCreateSpec(request, plan, session);
        BootstrapContext bootstrapContext;
        GenerationPerformanceMonitorService.SpanTimer bootstrapSpan = startSpan(
                session, "create_bootstrap", GenerationSpanCategory.WORKSPACE);
        // 将可能失败的操作收敛在统一异常边界内，便于清理资源和转换错误。
        try {
            bootstrapContext = bootstrap(app, request, plan);
            bootstrapSpan.close(
                    bootstrapContext.success() ? "success" : "failed",
                    String.valueOf(bootstrapContext.payload())
            );
        } catch (RuntimeException | Error failure) {
            createSpecTask.cancel();
            bootstrapSpan.failed(failure.getClass().getSimpleName());
            throw failure;
        }
        if (!bootstrapContext.success()) {
            createSpecTask.cancel();
            return null;
        }
        CreateSpecService.SpecResult createSpecResult = resolveCreateSpec(
                createSpecTask, request, plan, session);
        emitStage(session, "CREATE 模板骨架已就绪，开始使用模板变量规格本地渲染 recipe...", Map.of(
                "stage", "create_spec_recipe",
                "baseTemplate", plan.baseTemplateId(),
                "originalSlotGroups", plan.slotGroups().size(),
                "executionSlotGroups", coalesceSlotGroups(plan.slotGroups()).size(),
                "specAvailable", createSpecResult.available(),
                "specSource", createSpecResult.reason(),
                "modelAttempted", createSpecResult.modelAttempted(),
                "bootstrap", bootstrapContext.payload()
        ));

        List<PatchOperation> operations = new ArrayList<>();
        List<String> filledSlots = new ArrayList<>();
        List<String> skippedSlots = new ArrayList<>();
        int totalChars = 0;
        int aiCalls = 0;
        List<String> degradeReasons = new ArrayList<>();
        List<SlotGroup> executionGroups = coalesceSlotGroups(plan.slotGroups());
        if (createSpecResult.modelAttempted()) {
            aiCalls = 1;
        }
        if (!createSpecResult.modelSucceeded()) {
            degradeReasons.add(createSpecResult.reason());
        }
        GenerationPerformanceMonitorService.SpanTimer recipeSpan = startSpan(
                session, "create_recipe_render", GenerationSpanCategory.PIPELINE);
        try {
            int groupIndex = 0;
            for (SlotGroup group : executionGroups) {
            groupIndex++;
            emitStage(session, "正在渲染模板变量作用域 " + groupIndex + "/" + executionGroups.size()
                    + "：" + group.templateId() + "（" + group.slotIds().size() + " 个变量 slot）", Map.of(
                    "stage", "recipe_group_started",
                    "groupId", group.groupId(),
                    "templateId", group.templateId(),
                    "slotIds", group.slotIds(),
                    "groupIndex", groupIndex,
                    "groupTotal", executionGroups.size()
            ));
            RecipeRenderResult recipeResult = tryRenderRecipe(
                    request, group, createSpecResult, session);
            if (recipeResult.available()) {
                operations.addAll(prefixOperations(bootstrapContext.prefixFor(group), recipeResult.patchOperations()));
                filledSlots.addAll(prefixSlotIds(bootstrapContext.prefixFor(group), recipeResult.filledSlots()));
                totalChars += recipeResult.totalChars();
                String specMessage = createSpecResult.modelSucceeded()
                        ? "AI CREATE 规格已生成，正在使用本地 recipe 写入代码："
                        : "已使用本地 CREATE 规格，正在通过 recipe 写入代码：";
                emitStage(session, specMessage + group.templateId(), Map.of(
                        "stage", "create_spec_recipe_applied",
                        "groupId", group.groupId(),
                        "templateId", group.templateId(),
                        "slotIds", group.slotIds(),
                        "specSource", createSpecResult.reason(),
                        "modelAttempted", createSpecResult.modelAttempted(),
                        "variables", recipeResult.manifest() == null ? Map.of() : recipeResult.manifest().variables(),
                        "patchOperationCount", recipeResult.patchOperations().size()
                ));
                continue;
            }
            String reason = "recipe_unsupported:" + group.templateId() + ":" + group.slotIds();
            LandingSlotFallbackRenderer.LandingFallback fallback =
                    landingSlotFallbackRenderer.fallback(request.message(), group, reason);
            if (fallback.available()) {
                operations.addAll(prefixOperations(bootstrapContext.prefixFor(group), fallback.patchOperations()));
                filledSlots.addAll(prefixSlotIds(bootstrapContext.prefixFor(group), fallback.filledSlots()));
                totalChars += fallback.totalChars();
                degradeReasons.add("recipe_default_render:" + group.templateId() + ":" + group.slotIds());
                emitStage(session, "recipe 未覆盖该变量作用域，已使用本地默认渲染：" + group.templateId(), Map.of(
                        "stage", "recipe_default_rendered",
                        "groupId", group.groupId(),
                        "templateId", group.templateId(),
                        "slotIds", group.slotIds(),
                        "reason", reason,
                        "patchOperationCount", fallback.patchOperations().size()
                ));
                continue;
            }
            skippedSlots.addAll(prefixSlotIds(bootstrapContext.prefixFor(group), group.slotIds()));
            degradeReasons.add("recipe_skeleton_only:" + group.templateId() + ":" + group.slotIds());
            emitStage(session, "recipe 未覆盖该变量作用域，保留模板骨架继续生成：" + group.templateId(), Map.of(
                    "stage", "recipe_skeleton_only",
                    "groupId", group.groupId(),
                    "templateId", group.templateId(),
                    "slotIds", group.slotIds(),
                    "reason", reason
            ));
            }
            recipeSpan.close(
                    degradeReasons.isEmpty() ? "success" : "degraded",
                    degradeReasons.isEmpty() ? "" : String.join(";", degradeReasons)
            );
        } catch (RuntimeException | Error failure) {
            recipeSpan.failed(failure.getClass().getSimpleName());
            throw failure;
        }
        if (operations.isEmpty()) {
            return skeletonOnlyResult(plan, bootstrapContext, filledSlots, totalChars, skippedSlots, aiCalls,
                    executionGroups.size(), degradeReasons);
        }

        SlotPatchPlan patchPlan = createPatchMergeService.merge(operations);
        emitStage(session, "recipe 渲染完成，正在执行写入前校验...", Map.of(
                "stage", "pre_write_validation",
                "originalPatchCount", patchPlan.originalOperationCount(),
                "mergedPatchCount", patchPlan.mergedOperationCount(),
                "filledSlots", filledSlots.size()
        ));
        CreatePreWriteValidationService.ValidationResult validationResult;
        GenerationPerformanceMonitorService.SpanTimer preWriteValidationSpan = startSpan(
                session, "create_pre_write_validation", GenerationSpanCategory.VALIDATION);
        try {
            validationResult = createPreWriteValidationService.validate(patchPlan.operations());
            preWriteValidationSpan.close(
                    validationResult.valid() ? "success" : "failed",
                    validationResult.valid() ? "" : String.valueOf(validationResult.errors())
            );
        } catch (RuntimeException | Error failure) {
            preWriteValidationSpan.failed(failure.getClass().getSimpleName());
            throw failure;
        }
        if (!validationResult.valid()) {
            return failureResult(plan, filledSlots, totalChars, skippedSlots, aiCalls, patchPlan,
                    validationResult.durationMs(), executionGroups.size(), "pre_write_validation_failed:" + validationResult.errors());
        }

        String taskId = session == null ? null : session.taskId();
        if (taskId == null || taskId.isBlank()) {
            taskId = "create_template_" + System.currentTimeMillis();
        }
        emitStage(session, "写入校验通过，正在应用模板 patch...", Map.of(
                "stage", "patch_apply",
                "patchCount", patchPlan.mergedOperationCount()
        ));
        PatchApplyResult applyResult;
        GenerationPerformanceMonitorService.SpanTimer patchApplySpan = startSpan(
                session, "create_patch_apply", GenerationSpanCategory.TOOL);
        try {
            applyResult = generationPatchApplyService.applyWithoutChangePlan(
                    app.getId(),
                    taskId,
                    bootstrapContext.projectRoot(),
                    patchPlan.operations(),
                    "create_template_runtime"
            );
            patchApplySpan.close(
                    "applied".equals(applyResult.status()) ? "success" : "failed",
                    "applied".equals(applyResult.status()) ? "" : applyResult.reason()
            );
        } catch (RuntimeException | Error failure) {
            patchApplySpan.failed(failure.getClass().getSimpleName());
            throw failure;
        }
        if (!"applied".equals(applyResult.status())) {
            return failureResult(plan, filledSlots, totalChars, skippedSlots, aiCalls, patchPlan,
                    validationResult.durationMs(), executionGroups.size(), "patch_apply_" + applyResult.status() + ":" + applyResult.reason());
        }
        emitStage(session, "CREATE 模板 patch 已写入，准备进入构建验证...", Map.of(
                "stage", "patch_applied",
                "appliedFiles", applyResult.appliedFiles(),
                "appliedOperationCount", applyResult.appliedOperationCount()
        ));

        Map<String, Object> metadata = metadata(plan, bootstrapContext, aiCalls, patchPlan,
                validationResult.durationMs(), executionGroups.size(), false, "", degradeReasons);
        return new SlotFillResult(
                plan.baseTemplateId(),
                filledSlots,
                patchPlan.operations(),
                degradeReasons.isEmpty()
                        ? "CREATE 模板运行时完成，已渲染 " + filledSlots.size() + " 个变量作用域"
                        : "CREATE 模板运行时完成，已渲染 " + filledSlots.size() + " 个变量作用域（部分内容使用本地默认骨架）",
                totalChars,
                skippedSlots,
                metadata
        );
    }

    /** 提交并返回创建{@code Spec}。 */
    private CreateSpecTask submitCreateSpec(GenerationTaskRequest request,
                                            CreateGenerationPlan plan,
                                            GenerationSession session) {
        if (createSpecService == null) {
            return CreateSpecTask.submitted(CompletableFuture.completedFuture(
                    new CreateSpecService.SpecResult(false, null, "create_spec_service_unavailable")));
        }
        emitStage(session, "正在生成本次 CREATE 统一规格", Map.of(
                "stage", "create_spec_started",
                "baseTemplate", plan.baseTemplateId(),
                "slotGroupCount", plan.slotGroups().size()
        ));
        try {
            Future<CreateSpecService.SpecResult> future = createSpecTaskExecutor.submit(
                    monitorContextSnapshot(request, session),
                    () -> generateCreateSpecModel(request, plan, session == null ? null : session.taskId())
            );
            return CreateSpecTask.submitted(future);
        } catch (RejectedExecutionException saturated) {
            emitStage(session, "CREATE 规格并行容量已满，将在模板准备完成后继续生成", Map.of(
                    "stage", "create_spec_execution_deferred",
                    "reason", "executor_saturated"
            ));
            return CreateSpecTask.deferredExecution();
        }
    }

    /** 根据当前上下文解析创建{@code Spec}。 */
    private CreateSpecService.SpecResult resolveCreateSpec(CreateSpecTask createSpecTask,
                                                           GenerationTaskRequest request,
                                                           CreateGenerationPlan plan,
                                                           GenerationSession session) {
        CreateSpecService.SpecResult specResult = createSpecTask.deferred()
                ? generateCreateSpecModel(request, plan, session == null ? null : session.taskId())
                : awaitCreateSpec(createSpecTask.future(), request, plan, session);
        if (!specResult.available()) {
            emitStage(session, "CREATE 规格不可用，后续保留模板骨架", Map.of(
                    "stage", "create_spec_degraded",
                    "reason", specResult.reason()
            ));
        }
        return specResult;
    }

    /** 根据输入生成创建{@code Spec}模型。 */
    private CreateSpecService.SpecResult generateCreateSpecModel(GenerationTaskRequest request,
                                                                 CreateGenerationPlan plan,
                                                                 String taskId) {
        CreateSpecService.SpecResult specResult;
        Instant specStartedAt = Instant.now();
        MonitorContext previousMonitorContext = bindMonitorContext(request, taskId);
        GenerationPerformanceMonitorService.SpanTimer specSpan = startSpan(
                taskId, "create_spec_model", GenerationSpanCategory.MODEL);
        try {
            specResult = taskId == null || taskId.isBlank()
                    ? createSpecService.generate(request.message(), plan)
                    : createSpecService.generateManaged(taskId, request.message(), plan);
            String outcome = specResult.modelSucceeded()
                    ? "success"
                    : specResult.modelAttempted() ? "degraded" : "skipped";
            specSpan.close(outcome, specResult.reason());
            if (specResult.modelSucceeded()) {
                recordFirstModelSignal(taskId, specStartedAt, "create_spec");
            }
        } catch (RuntimeException | Error failure) {
            specSpan.failed(failure.getClass().getSimpleName());
            throw failure;
        } finally {
            restoreMonitorContext(previousMonitorContext);
        }
        return specResult;
    }

    /** 等待创建{@code Spec}完成。 */
    private CreateSpecService.SpecResult awaitCreateSpec(
            Future<CreateSpecService.SpecResult> future,
            GenerationTaskRequest request,
            CreateGenerationPlan plan,
            GenerationSession session
    ) {
        // 将可能失败的操作收敛在统一异常边界内，便于清理资源和转换错误。
        try {
            GenerationExecutionContext executionContext = session == null ? null : session.executionContext();
            if (executionContext == null) {
                return future.get();
            }
            while (true) {
                session.throwIfCancelled();
                if (future.isDone()) {
                    return future.get();
                }
                Optional<Duration> previewWindow =
                        executionContext.optionalFirstPreviewOperationTimeout(SPEC_WAIT_POLL_INTERVAL);
                if (previewWindow.isEmpty()) {
                    future.cancel(true);
                    emitStage(session, "CREATE 规格未在首预览质量窗口内完成，已切换本地规格继续生成", Map.of(
                            "stage", "create_spec_preview_deadline_degraded",
                            "reason", "first_preview_completion_reserve",
                            "taskId", executionContext.taskId()
                    ));
                    return createSpecService.generateLocal(
                            request.message(),
                            plan,
                            "local_spec_first_preview_wait_cutoff"
                    );
                }
                long waitNanos = Math.max(1L, Math.min(
                        previewWindow.orElseThrow().toNanos(), SPEC_WAIT_POLL_INTERVAL.toNanos()));
                try {
                    CreateSpecService.SpecResult result = future.get(waitNanos, TimeUnit.NANOSECONDS);
                    session.throwIfCancelled();
                    return result;
                } catch (TimeoutException ignored) {
                    // 轮询任务控制面，确保用户取消不必等待模型调用自行返回。
                }
            }
        } catch (InterruptedException interrupted) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            if (session != null) {
                session.throwIfCancelled();
            }
            throw new IllegalStateException("等待 CREATE 规格生成时被中断", interrupted);
        } catch (CancellationException cancelled) {
            if (session != null) {
                session.throwIfCancelled();
            }
            throw new IllegalStateException("CREATE 规格生成已取消", cancelled);
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof RuntimeException runtimeFailure) {
                throw runtimeFailure;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("CREATE 规格生成失败", cause);
        } catch (RuntimeException | Error failure) {
            future.cancel(true);
            throw failure;
        }
    }

    private GenerationPerformanceMonitorService.SpanTimer startSpan(
            GenerationSession session,
            String stage,
            GenerationSpanCategory category
    ) {
        String taskId = session == null ? null : session.taskId();
        return startSpan(taskId, stage, category);
    }

    private GenerationPerformanceMonitorService.SpanTimer startSpan(
            String taskId,
            String stage,
            GenerationSpanCategory category
    ) {
        return generationPerformanceMonitorService.startSpan(taskId, stage, category);
    }

    /** 记录{@code First}模型{@code Signal}相关指标或状态。 */
    private void recordFirstModelSignal(String taskId,
                                        Instant startedAt,
                                        String detail) {
        Duration latency = Duration.between(startedAt, Instant.now());
        long latencyMs = Math.max(1L, latency.toMillis());
        generationPerformanceMonitorService.recordSpan(
                taskId,
                "model_time_to_first_signal",
                GenerationSpanCategory.MODEL,
                "success",
                latency,
                detail
        );
        generationPerformanceMonitorService.recordRuntimeTelemetry(
                taskId, Map.of("firstTokenLatencyMs", latencyMs));
    }

    private MonitorContext monitorContextSnapshot(GenerationTaskRequest request,
                                                  GenerationSession session) {
        String taskId = session == null ? null : session.taskId();
        if (!hasMonitorIdentity(request, taskId)) {
            return MonitorContextHolder.getContext();
        }
        return MonitorContext.builder()
                .userId(request.loginUser().getId().toString())
                .appId(request.app().getId().toString())
                .taskId(taskId)
                .build();
    }

    /** 绑定{@code Monitor}上下文。 */
    private MonitorContext bindMonitorContext(GenerationTaskRequest request,
                                              String taskId) {
        MonitorContext previousContext = MonitorContextHolder.getContext();
        if (!hasMonitorIdentity(request, taskId)) {
            return previousContext;
        }
        MonitorContextHolder.setContext(MonitorContext.builder()
                .userId(request.loginUser().getId().toString())
                .appId(request.app().getId().toString())
                .taskId(taskId)
                .build());
        return previousContext;
    }

    private boolean hasMonitorIdentity(GenerationTaskRequest request, String taskId) {
        return request != null
                && request.app() != null
                && request.app().getId() != null
                && request.loginUser() != null
                && request.loginUser().getId() != null
                && taskId != null
                && !taskId.isBlank();
    }

    private void restoreMonitorContext(MonitorContext previousContext) {
        if (previousContext == null) {
            MonitorContextHolder.clearContext();
        } else {
            MonitorContextHolder.setContext(previousContext);
        }
    }

    /** 返回{@code try}{@code Render}{@code Recipe}。 */
    private RecipeRenderResult tryRenderRecipe(GenerationTaskRequest request,
                                               SlotGroup group,
                                               CreateSpecService.SpecResult specResult,
                                               GenerationSession session) {
        if (createRecipeRendererService == null) {
            return RecipeRenderResult.empty();
        }
        if (!specResult.available()) {
            emitStage(session, "CREATE 规格不可用，保留模板骨架：" + group.templateId(), Map.of(
                    "stage", "create_spec_group_degraded",
                    "groupId", group.groupId(),
                    "templateId", group.templateId(),
                    "reason", specResult.reason()
            ));
            return RecipeRenderResult.empty();
        }
        RecipeRenderResult result = createRecipeRendererService.render(
                request.message(),
                group,
                specResult.spec()
        );
        if (!result.available()) {
            emitStage(session, "CREATE recipe 暂未覆盖该变量作用域，后续使用本地默认或骨架：" + group.templateId(), Map.of(
                    "stage", "create_recipe_unsupported",
                    "groupId", group.groupId(),
                    "templateId", group.templateId(),
                    "slotIds", group.slotIds()
            ));
        }
        return result;
    }

    /** 复用共享模板初始化 module，并投影为 CREATE runtime 上下文。 */
    private BootstrapContext bootstrap(App app, GenerationTaskRequest request, CreateGenerationPlan plan) {
        GenerationTemplateBootstrapResult result = templateBootstrapRegistry.bootstrap(
                app.getId(), plan.codeGenType(), request.message());
        if (!result.supported()) {
            return BootstrapContext.failed(result.templatePayload());
        }
        return new BootstrapContext(
                result.successful(),
                result.projectRoot(),
                !result.contextPayload().isEmpty(),
                result.runtimePayload()
        );
    }

    /** 将{@code ure}结果标记为失败并记录原因。 */
    private SlotFillResult failureResult(CreateGenerationPlan plan,
                                         List<String> filledSlots,
                                         int totalChars,
                                         List<String> skippedSlots,
                                         int aiCalls,
                                         SlotPatchPlan patchPlan,
                                         long validationDurationMs,
                                         int executionSlotGroupCount,
                                         String fallbackReason) {
        return new SlotFillResult(
                plan.baseTemplateId(),
                filledSlots,
                List.of(),
                "CREATE 模板运行时失败: " + fallbackReason,
                totalChars,
                skippedSlots,
                metadata(plan, BootstrapContext.failed(Map.of()), aiCalls, patchPlan,
                        validationDurationMs, executionSlotGroupCount, true, fallbackReason, List.of())
        );
    }

    /** 返回骨架仅结果。 */
    private SlotFillResult skeletonOnlyResult(CreateGenerationPlan plan,
                                              BootstrapContext bootstrapContext,
                                              List<String> filledSlots,
                                              int totalChars,
                                              List<String> skippedSlots,
                                              int aiCalls,
                                              int executionSlotGroupCount,
                                              List<String> degradeReasons) {
        List<String> safeDegradeReasons = degradeReasons == null || degradeReasons.isEmpty()
                ? List.of("recipe_no_patch_skeleton_only")
                : degradeReasons;
        return new SlotFillResult(
                plan.baseTemplateId(),
                filledSlots,
                List.of(),
                "CREATE 模板骨架已生成，recipe 未产生可写入 patch，保留模板默认内容进入构建验证",
                totalChars,
                skippedSlots,
                metadata(plan, bootstrapContext, aiCalls, null,
                        0L, executionSlotGroupCount, false, "", safeDegradeReasons)
        );
    }

    /** 返回元数据。 */
    private Map<String, Object> metadata(CreateGenerationPlan plan,
                                         BootstrapContext bootstrapContext,
                                         int aiCalls,
                                         SlotPatchPlan patchPlan,
                                         long validationDurationMs,
                                         int executionSlotGroupCount,
                                         boolean fallback,
                                         String fallbackReason,
                                         List<String> degradeReasons) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("plan", plan.toPayload());
        metadata.put("bootstrap", bootstrapContext.payload());
        metadata.put("telemetry", new CreateGenerationTelemetry(
                plan.baseTemplateId(),
                plan.moduleIds(),
                executionSlotGroupCount,
                aiCalls,
                patchPlan == null ? 0 : patchPlan.mergedOperationCount(),
                validationDurationMs,
                fallback,
                fallbackReason,
                degradeReasons != null && !degradeReasons.isEmpty(),
                degradeReasons
        ).toPayload());
        return metadata;
    }

    /** 返回{@code coalesce}插槽{@code Groups}。 */
    private List<SlotGroup> coalesceSlotGroups(List<SlotGroup> slotGroups) {
        if (slotGroups == null || slotGroups.isEmpty()) {
            return List.of();
        }
        Map<String, CoalescedSlotGroup> groupsByTemplate = new LinkedHashMap<>();
        for (SlotGroup group : slotGroups.stream().sorted(java.util.Comparator.comparingInt(SlotGroup::order)).toList()) {
            if (group == null || StrUtil.isBlank(group.templateId()) || group.slotIds().isEmpty()) {
                continue;
            }
            String key = group.templateId();
            CoalescedSlotGroup coalesced = groupsByTemplate.computeIfAbsent(
                    key,
                    ignored -> new CoalescedSlotGroup(group.templateId(), group.order())
            );
            coalesced.add(group);
        }
        return groupsByTemplate.values().stream()
                .map(CoalescedSlotGroup::toSlotGroup)
                .toList();
    }

    private void emitStage(GenerationSession session, String message, Map<String, Object> data) {
        if (session != null && session.isActive()) {
            session.emit(GenerationStreamEvent.generationStage(message, data));
        }
    }

    /** 返回{@code prefix}操作。 */
    private List<PatchOperation> prefixOperations(String prefix, List<PatchOperation> operations) {
        if (StrUtil.isBlank(prefix) || operations == null || operations.isEmpty()) {
            return operations == null ? List.of() : operations;
        }
        return operations.stream()
                .map(operation -> new PatchOperation(
                        operation.action(),
                        prefix + operation.relativePath(),
                        operation.content(),
                        operation.oldContent(),
                        operation.newContent()
                ))
                .toList();
    }

    /** 返回{@code prefix}插槽{@code Ids}。 */
    private List<String> prefixSlotIds(String prefix, List<String> slotIds) {
        if (slotIds == null || slotIds.isEmpty()) {
            return List.of();
        }
        String normalizedPrefix = StrUtil.blankToDefault(prefix, "");
        if (StrUtil.isBlank(normalizedPrefix)) {
            return slotIds;
        }
        return slotIds.stream().map(slotId -> normalizedPrefix + slotId).toList();
    }

    private record BootstrapContext(
            boolean success,
            Path projectRoot,
            boolean fullStack,
            Map<String, Object> payload
    ) {
        private static BootstrapContext failed(Map<String, Object> payload) {
            return new BootstrapContext(false, null, false, payload == null ? Map.of() : payload);
        }

        private String prefixFor(SlotGroup group) {
            if (!fullStack) {
                return "";
            }
            return BACKEND_TEMPLATE.equals(group.templateId()) ? "backend/" : "frontend/";
        }
    }

    private record CreateSpecTask(Future<CreateSpecService.SpecResult> future, boolean deferred) {

        private static CreateSpecTask submitted(Future<CreateSpecService.SpecResult> future) {
            return new CreateSpecTask(future, false);
        }

        private static CreateSpecTask deferredExecution() {
            return new CreateSpecTask(null, true);
        }

        private void cancel() {
            if (future != null) {
                future.cancel(true);
            }
        }
    }

    private static final class CoalescedSlotGroup {
        private final String templateId;
        private final int order;
        private final List<String> moduleIds = new ArrayList<>();
        private final List<String> slotIds = new ArrayList<>();

        private CoalescedSlotGroup(String templateId, int order) {
            this.templateId = templateId;
            this.order = order;
        }

        /** 添加{@code Coalesced}插槽分组。 */
        private void add(SlotGroup group) {
            if (StrUtil.isNotBlank(group.moduleId()) && !moduleIds.contains(group.moduleId())) {
                moduleIds.add(group.moduleId());
            }
            for (String slotId : group.slotIds()) {
                if (StrUtil.isNotBlank(slotId) && !slotIds.contains(slotId)) {
                    slotIds.add(slotId);
                }
            }
        }

        private SlotGroup toSlotGroup() {
            String moduleId = moduleIds.isEmpty() ? "coalesced" : String.join("+", moduleIds);
            return new SlotGroup(templateId + "-coalesced-slots", templateId, moduleId, slotIds, order);
        }
    }
}
