package com.rush.rushaicodemother.orchestration.template;

import cn.hutool.core.util.StrUtil;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.GenerationSession;
import com.rush.rushaicodemother.orchestration.GenerationTaskRequest;
import com.rush.rushaicodemother.orchestration.create.CreateGenerationPlan;
import com.rush.rushaicodemother.orchestration.create.CreateTemplatePlanner;
import com.rush.rushaicodemother.orchestration.create.CreateTemplateRuntime;
import com.rush.rushaicodemother.orchestration.event.GenerationEventPublisher;
import com.rush.rushaicodemother.orchestration.event.GenerationEventType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class SlotFillGenerationService {

    private final CreateTemplatePlanner createTemplatePlanner;
    private final CreateTemplateRuntime createTemplateRuntime;
    private final GenerationEventPublisher generationEventPublisher;
    private final TemplateSlotFillService templateSlotFillService;
    private final ThreadLocal<String> lastFailureReason = new ThreadLocal<>();

    public SlotFillResult tryGenerate(App app, GenerationTaskRequest request) {
        return tryGenerate(app, request, null);
    }

    public SlotFillResult tryGenerate(App app, GenerationTaskRequest request, GenerationSession session) {
        lastFailureReason.remove();
        if (app == null || request == null) {
            lastFailureReason.set("invalid_create_request");
            return null;
        }
        CodeGenTypeEnum codeGenType = CodeGenTypeEnum.getEnumByValue(app.getCodeGenType());
        if (codeGenType == null) {
            lastFailureReason.set("unsupported_code_gen_type");
            return null;
        }
        CreateGenerationPlan plan = createTemplatePlanner.plan(codeGenType, request.message());
        if (plan == null || StrUtil.isBlank(plan.baseTemplateId()) || plan.slotGroups().isEmpty()) {
            log.debug("CREATE 模板计划不可用: {}", plan == null ? "null" : plan.reason());
            lastFailureReason.set("create_plan_unavailable");
            return null;
        }
        if (codeGenType != CodeGenTypeEnum.FULL_STACK_PROJECT && !templateSlotFillService.supportsSlotFill(plan.baseTemplateId())) {
            log.debug("模板不支持 slot 填充: {}", plan.baseTemplateId());
            lastFailureReason.set("template_slot_fill_unsupported:" + plan.baseTemplateId());
            return null;
        }

        SlotFillResult result = createTemplateRuntime.generate(app, request, plan, session);
        if (result == null) {
            lastFailureReason.set("create_template_runtime_returned_null");
            log.debug("CREATE 模板运行时未成功: null");
            return null;
        }
        if (result.fallback()) {
            lastFailureReason.set(StrUtil.blankToDefault(result.fallbackReason(), result.summary()));
            log.debug("CREATE 模板运行时未成功: {}", result == null ? "null" : result.summary());
            return null;
        }
        generationEventPublisher.publish(request, GenerationEventType.TASK_ROUTE, "使用 CREATE 模板运行时", Map.of(
                "route", "create_template",
                "templateId", result.templateId(),
                "filledSlots", result.filledSlots(),
                "totalChars", result.totalChars(),
                "createPlan", plan.toPayload(),
                "telemetry", result.telemetry()
        ));
        return result;
    }

    public String consumeLastFailureReason() {
        String reason = lastFailureReason.get();
        lastFailureReason.remove();
        return StrUtil.blankToDefault(reason, "CREATE 模板生成未产生可写入的 slot patch");
    }

}
