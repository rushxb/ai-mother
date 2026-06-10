package com.rush.rushaicodemother.orchestration.create;

import cn.hutool.core.util.StrUtil;
import com.rush.rushaicodemother.constant.AppConstant;
import com.rush.rushaicodemother.core.handler.GenerationStreamEvent;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.GenerationSession;
import com.rush.rushaicodemother.orchestration.GenerationTaskRequest;
import com.rush.rushaicodemother.orchestration.artifact.PatchApplyResult;
import com.rush.rushaicodemother.orchestration.fullstack.FullStackGenerationContext;
import com.rush.rushaicodemother.orchestration.fullstack.FullStackPortAllocator;
import com.rush.rushaicodemother.orchestration.patch.GenerationPatchApplyService;
import com.rush.rushaicodemother.orchestration.patch.PatchOperation;
import com.rush.rushaicodemother.orchestration.template.BackendProjectTemplateBootstrapService;
import com.rush.rushaicodemother.orchestration.template.SlotFillResult;
import com.rush.rushaicodemother.orchestration.template.TemplateSlotFillService;
import com.rush.rushaicodemother.orchestration.template.VueProjectTemplateBootstrapService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class CreateTemplateRuntime {

    private static final String BACKEND_TEMPLATE = "go-sqlite-backend-basic";

    private final BackendProjectTemplateBootstrapService backendProjectTemplateBootstrapService;
    private final CreatePatchMergeService createPatchMergeService;
    private final CreatePreWriteValidationService createPreWriteValidationService;
    private final FullStackPortAllocator fullStackPortAllocator;
    private final GenerationPatchApplyService generationPatchApplyService;
    private final LandingSlotFallbackRenderer landingSlotFallbackRenderer;
    private final TemplateSlotFillService templateSlotFillService;
    private final VueProjectTemplateBootstrapService vueProjectTemplateBootstrapService;

    public SlotFillResult generate(App app, GenerationTaskRequest request, CreateGenerationPlan plan) {
        return generate(app, request, plan, null);
    }

    public SlotFillResult generate(App app, GenerationTaskRequest request, CreateGenerationPlan plan, GenerationSession session) {
        if (app == null || request == null || plan == null || plan.slotGroups().isEmpty()) {
            return null;
        }
        BootstrapContext bootstrapContext = bootstrap(app, request, plan);
        if (!bootstrapContext.success()) {
            return null;
        }
        emitStage(session, "CREATE 模板骨架已就绪，开始按模板合批填充 slot...", Map.of(
                "stage", "slot_fill",
                "baseTemplate", plan.baseTemplateId(),
                "originalSlotGroups", plan.slotGroups().size(),
                "executionSlotGroups", coalesceSlotGroups(plan.slotGroups()).size(),
                "bootstrap", bootstrapContext.payload()
        ));

        List<PatchOperation> operations = new ArrayList<>();
        List<String> filledSlots = new ArrayList<>();
        List<String> skippedSlots = new ArrayList<>();
        int totalChars = 0;
        int aiCalls = 0;
        List<String> degradeReasons = new ArrayList<>();
        List<SlotGroup> executionGroups = coalesceSlotGroups(plan.slotGroups());
        int groupIndex = 0;
        for (SlotGroup group : executionGroups) {
            groupIndex++;
            emitStage(session, "正在填充 slot 组 " + groupIndex + "/" + executionGroups.size()
                    + "：" + group.templateId() + "（" + group.slotIds().size() + " 个 slot）", Map.of(
                    "stage", "slot_fill_group_started",
                    "groupId", group.groupId(),
                    "templateId", group.templateId(),
                    "slotIds", group.slotIds(),
                    "groupIndex", groupIndex,
                    "groupTotal", executionGroups.size()
            ));
            SlotFillResult groupResult = templateSlotFillService.fillSlots(
                    group.templateId(),
                    app.getId(),
                    request.message(),
                    group.slotIds()
            );
            aiCalls++;
            if (groupResult == null) {
                String groupFailureReason = templateSlotFillService.consumeLastFailureReason();
                String safeFailureReason = StrUtil.blankToDefault(groupFailureReason, "slot_fill_failed");
                LandingSlotFallbackRenderer.LandingFallback fallback =
                        landingSlotFallbackRenderer.fallback(request.message(), group, safeFailureReason);
                if (fallback.available()) {
                    operations.addAll(prefixOperations(bootstrapContext.prefixFor(group), fallback.patchOperations()));
                    filledSlots.addAll(prefixSlotIds(bootstrapContext.prefixFor(group), fallback.filledSlots()));
                    totalChars += fallback.totalChars();
                    degradeReasons.add("slot_group_fallback:" + group.templateId() + ":" + safeFailureReason);
                    emitStage(session, "slot 组 AI 填充失败，已使用本地模板数据兜底：" + group.templateId(), Map.of(
                            "stage", "slot_fill_group_degraded",
                            "groupId", group.groupId(),
                            "templateId", group.templateId(),
                            "slotIds", group.slotIds(),
                            "reason", safeFailureReason,
                            "patchOperationCount", fallback.patchOperations().size()
                    ));
                    continue;
                }
                skippedSlots.addAll(prefixSlotIds(bootstrapContext.prefixFor(group), group.slotIds()));
                degradeReasons.add("slot_group_skipped:" + group.templateId() + ":" + safeFailureReason);
                emitStage(session, "slot 组 AI 填充失败，保留模板默认内容继续生成：" + group.templateId(), Map.of(
                        "stage", "slot_fill_group_degraded",
                        "groupId", group.groupId(),
                        "templateId", group.templateId(),
                        "slotIds", group.slotIds(),
                        "reason", safeFailureReason
                ));
                continue;
            }
            if (!groupResult.allRequiredSlotsFilled()) {
                String reason = "required_slot_missing:" + group.templateId() + ":" + groupResult.skippedSlots();
                LandingSlotFallbackRenderer.LandingFallback fallback =
                        landingSlotFallbackRenderer.fallback(request.message(), group, reason);
                if (fallback.available()) {
                    operations.addAll(prefixOperations(bootstrapContext.prefixFor(group), fallback.patchOperations()));
                    filledSlots.addAll(prefixSlotIds(bootstrapContext.prefixFor(group), fallback.filledSlots()));
                    totalChars += fallback.totalChars();
                    degradeReasons.add("required_slot_fallback:" + group.templateId() + ":" + groupResult.skippedSlots());
                    emitStage(session, "slot 组返回不完整，已使用本地模板数据兜底：" + group.templateId(), Map.of(
                            "stage", "slot_fill_group_degraded",
                            "groupId", group.groupId(),
                            "templateId", group.templateId(),
                            "slotIds", group.slotIds(),
                            "reason", reason,
                            "patchOperationCount", fallback.patchOperations().size()
                    ));
                    continue;
                }
                operations.addAll(prefixOperations(bootstrapContext.prefixFor(group), groupResult.patchOperations()));
                filledSlots.addAll(prefixSlotIds(bootstrapContext.prefixFor(group), groupResult.filledSlots()));
                skippedSlots.addAll(prefixSlotIds(bootstrapContext.prefixFor(group), groupResult.skippedSlots()));
                totalChars += groupResult.totalChars();
                degradeReasons.add("required_slot_partial:" + group.templateId() + ":" + groupResult.skippedSlots());
                emitStage(session, "slot 组返回不完整，已保留可用 patch 并继续生成：" + group.templateId(), Map.of(
                        "stage", "slot_fill_group_degraded",
                        "groupId", group.groupId(),
                        "templateId", group.templateId(),
                        "slotIds", group.slotIds(),
                        "reason", reason,
                        "patchOperationCount", groupResult.patchOperationCount()
                ));
                continue;
            }
            if (groupResult.patchOperations() == null || groupResult.patchOperations().isEmpty()) {
                String reason = "slot_group_empty_patch:" + group.templateId() + ":" + group.slotIds();
                LandingSlotFallbackRenderer.LandingFallback fallback =
                        landingSlotFallbackRenderer.fallback(request.message(), group, reason);
                if (fallback.available()) {
                    operations.addAll(prefixOperations(bootstrapContext.prefixFor(group), fallback.patchOperations()));
                    filledSlots.addAll(prefixSlotIds(bootstrapContext.prefixFor(group), fallback.filledSlots()));
                    totalChars += fallback.totalChars();
                    degradeReasons.add("empty_patch_fallback:" + group.templateId() + ":" + group.slotIds());
                    emitStage(session, "slot 组没有生成 patch，已使用本地模板数据兜底：" + group.templateId(), Map.of(
                            "stage", "slot_fill_group_degraded",
                            "groupId", group.groupId(),
                            "templateId", group.templateId(),
                            "slotIds", group.slotIds(),
                            "reason", reason,
                            "patchOperationCount", fallback.patchOperations().size()
                    ));
                    continue;
                }
                skippedSlots.addAll(prefixSlotIds(bootstrapContext.prefixFor(group), group.slotIds()));
                degradeReasons.add("empty_patch_skipped:" + group.templateId() + ":" + group.slotIds());
                emitStage(session, "slot 组没有生成 patch，保留模板默认内容继续生成：" + group.templateId(), Map.of(
                        "stage", "slot_fill_group_degraded",
                        "groupId", group.groupId(),
                        "templateId", group.templateId(),
                        "slotIds", group.slotIds(),
                        "reason", reason
                ));
                continue;
            }
            if (groupResult.filledSlotCount() < group.slotIds().size()) {
                String reason = "slot_group_incomplete:" + group.templateId() + ":" + group.slotIds();
                LandingSlotFallbackRenderer.LandingFallback fallback =
                        landingSlotFallbackRenderer.fallback(request.message(), group, reason);
                if (fallback.available()) {
                    operations.addAll(prefixOperations(bootstrapContext.prefixFor(group), fallback.patchOperations()));
                    filledSlots.addAll(prefixSlotIds(bootstrapContext.prefixFor(group), fallback.filledSlots()));
                    totalChars += fallback.totalChars();
                    degradeReasons.add("incomplete_slot_fallback:" + group.templateId() + ":" + group.slotIds());
                    emitStage(session, "slot 组未填满，已使用本地模板数据兜底：" + group.templateId(), Map.of(
                            "stage", "slot_fill_group_degraded",
                            "groupId", group.groupId(),
                            "templateId", group.templateId(),
                            "slotIds", group.slotIds(),
                            "reason", reason,
                            "patchOperationCount", fallback.patchOperations().size()
                    ));
                    continue;
                }
                operations.addAll(prefixOperations(bootstrapContext.prefixFor(group), groupResult.patchOperations()));
                filledSlots.addAll(prefixSlotIds(bootstrapContext.prefixFor(group), groupResult.filledSlots()));
                skippedSlots.addAll(prefixSlotIds(bootstrapContext.prefixFor(group), group.slotIds()));
                totalChars += groupResult.totalChars();
                degradeReasons.add("incomplete_slot_partial:" + group.templateId() + ":" + group.slotIds());
                emitStage(session, "slot 组未填满，已保留可用 patch 并继续生成：" + group.templateId(), Map.of(
                        "stage", "slot_fill_group_degraded",
                        "groupId", group.groupId(),
                        "templateId", group.templateId(),
                        "slotIds", group.slotIds(),
                        "reason", reason,
                        "patchOperationCount", groupResult.patchOperationCount()
                ));
                continue;
            }
            operations.addAll(prefixOperations(bootstrapContext.prefixFor(group), groupResult.patchOperations()));
            filledSlots.addAll(prefixSlotIds(bootstrapContext.prefixFor(group), groupResult.filledSlots()));
            skippedSlots.addAll(prefixSlotIds(bootstrapContext.prefixFor(group), groupResult.skippedSlots()));
            totalChars += groupResult.totalChars();
            emitStage(session, "slot 组填充完成：" + group.templateId()
                    + "，已生成 " + groupResult.filledSlotCount() + " 个 slot", Map.of(
                    "stage", "slot_fill_group_done",
                    "groupId", group.groupId(),
                    "templateId", group.templateId(),
                    "filledSlots", prefixSlotIds(bootstrapContext.prefixFor(group), groupResult.filledSlots()),
                    "patchOperationCount", groupResult.patchOperationCount(),
                    "totalChars", groupResult.totalChars()
            ));
        }
        if (operations.isEmpty()) {
            return skeletonOnlyResult(plan, bootstrapContext, filledSlots, totalChars, skippedSlots, aiCalls,
                    executionGroups.size(), degradeReasons);
        }

        SlotPatchPlan patchPlan = createPatchMergeService.merge(operations);
        emitStage(session, "slot 填充完成，正在执行写入前校验...", Map.of(
                "stage", "pre_write_validation",
                "originalPatchCount", patchPlan.originalOperationCount(),
                "mergedPatchCount", patchPlan.mergedOperationCount(),
                "filledSlots", filledSlots.size()
        ));
        CreatePreWriteValidationService.ValidationResult validationResult =
                createPreWriteValidationService.validate(patchPlan.operations());
        if (!validationResult.valid()) {
            return failureResult(plan, filledSlots, totalChars, skippedSlots, aiCalls, patchPlan,
                    validationResult.durationMs(), executionGroups.size(), "pre_write_validation_failed:" + validationResult.errors());
        }

        String taskId = "create_template_" + System.currentTimeMillis();
        emitStage(session, "写入校验通过，正在应用模板 patch...", Map.of(
                "stage", "patch_apply",
                "patchCount", patchPlan.mergedOperationCount()
        ));
        PatchApplyResult applyResult = generationPatchApplyService.applyWithoutChangePlan(
                app.getId(),
                taskId,
                bootstrapContext.projectRoot(),
                patchPlan.operations(),
                "create_template_runtime"
        );
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
                        ? "CREATE 模板运行时完成，已生成 " + filledSlots.size() + " 个 slot"
                        : "CREATE 模板运行时完成，已生成 " + filledSlots.size() + " 个 slot（部分内容使用本地兜底）",
                totalChars,
                skippedSlots,
                metadata
        );
    }

    private BootstrapContext bootstrap(App app, GenerationTaskRequest request, CreateGenerationPlan plan) {
        CodeGenTypeEnum codeGenType = plan.codeGenType();
        if (codeGenType == CodeGenTypeEnum.VUE_PROJECT) {
            VueProjectTemplateBootstrapService.BootstrapResult result =
                    vueProjectTemplateBootstrapService.bootstrapIfNecessary(app.getId(), request.message());
            return BootstrapContext.single(result.bootstrapped(), vueProjectTemplateBootstrapService.resolveProjectRoot(app.getId()),
                    Map.of("frontend", result.toPayload()));
        }
        if (codeGenType == CodeGenTypeEnum.BACKEND_PROJECT) {
            BackendProjectTemplateBootstrapService.BootstrapResult result =
                    backendProjectTemplateBootstrapService.bootstrapIfNecessary(app.getId());
            return BootstrapContext.single(result.bootstrapped(), backendProjectTemplateBootstrapService.resolveProjectRoot(app.getId()),
                    Map.of("backend", result.toPayload()));
        }
        if (codeGenType == CodeGenTypeEnum.FULL_STACK_PROJECT) {
            FullStackGenerationContext fullStackContext = fullStackPortAllocator.allocate(app.getId());
            Path workspaceRoot = Path.of(fullStackContext.workspaceRoot()).toAbsolutePath().normalize();
            VueProjectTemplateBootstrapService.BootstrapResult frontendResult =
                    vueProjectTemplateBootstrapService.bootstrapIfNecessary(workspaceRoot.resolve("frontend"), request.message());
            BackendProjectTemplateBootstrapService.BootstrapResult backendResult =
                    backendProjectTemplateBootstrapService.bootstrapIfNecessary(workspaceRoot.resolve("backend"));
            Map<String, Object> payload = new LinkedHashMap<>(fullStackContext.toPayload());
            payload.put("frontend", frontendResult.toPayload());
            payload.put("backend", backendResult.toPayload());
            return BootstrapContext.fullStack(
                    frontendResult.bootstrapped() && backendResult.bootstrapped(),
                    workspaceRoot,
                    payload
            );
        }
        Path fallbackRoot = Path.of(AppConstant.CODE_OUTPUT_ROOT_DIR + File.separator
                + codeGenType.getValue() + "_" + app.getId()).toAbsolutePath().normalize();
        return BootstrapContext.single(false, fallbackRoot, Map.of("reason", "unsupported_code_gen_type"));
    }

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
                metadata(plan, BootstrapContext.single(false, Path.of(""), Map.of()), aiCalls, patchPlan,
                        validationDurationMs, executionSlotGroupCount, true, fallbackReason, List.of())
        );
    }

    private SlotFillResult skeletonOnlyResult(CreateGenerationPlan plan,
                                              BootstrapContext bootstrapContext,
                                              List<String> filledSlots,
                                              int totalChars,
                                              List<String> skippedSlots,
                                              int aiCalls,
                                              int executionSlotGroupCount,
                                              List<String> degradeReasons) {
        List<String> safeDegradeReasons = degradeReasons == null || degradeReasons.isEmpty()
                ? List.of("slot_fill_no_patch_skeleton_only")
                : degradeReasons;
        return new SlotFillResult(
                plan.baseTemplateId(),
                filledSlots,
                List.of(),
                "CREATE 模板骨架已生成，AI slot 未产生可写入 patch，保留模板默认内容进入构建验证",
                totalChars,
                skippedSlots,
                metadata(plan, bootstrapContext, aiCalls, null,
                        0L, executionSlotGroupCount, false, "", safeDegradeReasons)
        );
    }

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
        private static BootstrapContext single(boolean success, Path projectRoot, Map<String, Object> payload) {
            return new BootstrapContext(success, projectRoot, false, payload == null ? Map.of() : payload);
        }

        private static BootstrapContext fullStack(boolean success, Path projectRoot, Map<String, Object> payload) {
            return new BootstrapContext(success, projectRoot, true, payload == null ? Map.of() : payload);
        }

        private String prefixFor(SlotGroup group) {
            if (!fullStack) {
                return "";
            }
            return BACKEND_TEMPLATE.equals(group.templateId()) ? "backend/" : "frontend/";
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
