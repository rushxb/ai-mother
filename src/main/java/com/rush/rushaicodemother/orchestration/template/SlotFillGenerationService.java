package com.rush.rushaicodemother.orchestration.template;

import cn.hutool.core.util.StrUtil;
import com.rush.rushaicodemother.constant.AppConstant;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.GenerationTaskRequest;
import com.rush.rushaicodemother.orchestration.event.GenerationEventPublisher;
import com.rush.rushaicodemother.orchestration.event.GenerationEventType;
import com.rush.rushaicodemother.orchestration.patch.GenerationPatchApplyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Path;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class SlotFillGenerationService {

    private final BackendProjectTemplateBootstrapService backendProjectTemplateBootstrapService;
    private final GenerationEventPublisher generationEventPublisher;
    private final GenerationPatchApplyService generationPatchApplyService;
    private final ParallelSlotFillService parallelSlotFillService;
    private final TemplateSlotFillService templateSlotFillService;
    private final VueProjectTemplateBootstrapService vueProjectTemplateBootstrapService;

    public SlotFillResult tryGenerate(App app, GenerationTaskRequest request) {
        if (app == null || request == null) {
            return null;
        }
        CodeGenTypeEnum codeGenType = CodeGenTypeEnum.getEnumByValue(app.getCodeGenType());
        if (codeGenType == null) {
            return null;
        }
        String templateId = selectTemplateId(codeGenType, request.message());
        if (StrUtil.isBlank(templateId)) {
            log.debug("无法选择模板");
            return null;
        }
        if (codeGenType != CodeGenTypeEnum.FULL_STACK_PROJECT && !templateSlotFillService.supportsSlotFill(templateId)) {
            log.debug("模板不支持 slot 填充: {}", templateId);
            return null;
        }

        ParallelSlotFillService.ParallelSlotFillResult parallelResult =
                parallelSlotFillService.executeInParallel(templateId, app.getId(), request.message(), codeGenType);
        SlotFillResult result = parallelResult.success()
                ? parallelResult.slotFillResult()
                : tryGenerateSequential(app, request, templateId, codeGenType);
        if (result == null || result.patchOperations() == null || result.patchOperations().isEmpty()) {
            log.debug("Slot 填充未产生有效操作");
            return null;
        }
        if (!applyPatch(app, codeGenType, result)) {
            return null;
        }
        generationEventPublisher.publish(request, GenerationEventType.TASK_ROUTE, "使用模板 slot 填充路径", Map.of(
                "route", "slot_fill",
                "templateId", templateId,
                "filledSlots", result.filledSlots(),
                "totalChars", result.totalChars()
        ));
        return result;
    }

    private String selectTemplateId(CodeGenTypeEnum codeGenType, String userMessage) {
        return switch (codeGenType) {
            case VUE_PROJECT -> vueProjectTemplateBootstrapService.selectTemplateId(userMessage);
            case BACKEND_PROJECT -> "go-sqlite-backend-basic";
            case FULL_STACK_PROJECT -> vueProjectTemplateBootstrapService.selectTemplateId(userMessage)
                    + "+go-sqlite-backend-basic";
            default -> "";
        };
    }

    private SlotFillResult tryGenerateSequential(App app,
                                                 GenerationTaskRequest request,
                                                 String templateId,
                                                 CodeGenTypeEnum codeGenType) {
        if (codeGenType == CodeGenTypeEnum.VUE_PROJECT) {
            VueProjectTemplateBootstrapService.BootstrapResult bootstrapResult =
                    vueProjectTemplateBootstrapService.bootstrapIfNecessary(app.getId(), request.message());
            if (!bootstrapResult.bootstrapped() && "workspace_exists".equals(bootstrapResult.reason())) {
                return null;
            }
        } else if (codeGenType == CodeGenTypeEnum.BACKEND_PROJECT) {
            BackendProjectTemplateBootstrapService.BootstrapResult bootstrapResult =
                    backendProjectTemplateBootstrapService.bootstrapIfNecessary(app.getId());
            if (!bootstrapResult.bootstrapped() && "workspace_exists".equals(bootstrapResult.reason())) {
                return null;
            }
        } else if (codeGenType == CodeGenTypeEnum.FULL_STACK_PROJECT) {
            return null;
        }
        return templateSlotFillService.fillSlots(templateId, app.getId(), request.message());
    }

    private boolean applyPatch(App app, CodeGenTypeEnum codeGenType, SlotFillResult result) {
        try {
            String projectPath = AppConstant.CODE_OUTPUT_ROOT_DIR + File.separator
                    + codeGenType.getValue() + "_" + app.getId();
            Path projectRoot = Path.of(projectPath);
            generationPatchApplyService.applyWithoutChangePlan(
                    app.getId(),
                    "slot_fill_" + System.currentTimeMillis(),
                    projectRoot,
                    result.patchOperations(),
                    "slot_fill_generation"
            );
            log.info("Slot 填充 patch 应用成功: {} 个操作", result.patchOperations().size());
            return true;
        } catch (Exception e) {
            log.warn("Slot 填充 patch 应用失败: {}", e.getMessage());
            return false;
        }
    }
}
