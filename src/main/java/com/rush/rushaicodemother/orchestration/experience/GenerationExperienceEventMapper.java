package com.rush.rushaicodemother.orchestration.experience;

import com.rush.rushaicodemother.core.handler.GenerationStreamEvent;
import com.rush.rushaicodemother.orchestration.event.GenerationEvent;
import com.rush.rushaicodemother.orchestration.event.GenerationEventType;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 将内部生成事件投射为稳定、低噪声的用户进度事件。
 *
 * <p>内部事件仍保留给日志、指标和诊断使用；只有经过本映射器生成的有限字段才能作为
 * 用户主进度跨越 SSE 边界。审批事件是例外，它还需要保留完成审批所需的最小业务字段。</p>
 */
@Component
public class GenerationExperienceEventMapper {

    public static final String USER_AUDIENCE = "user";
    public static final int CONTRACT_VERSION = 1;
    public static final String USER_PROGRESS_STAGE_FIELD = "userProgressStage";
    public static final String USER_PROGRESS_MESSAGE_FIELD = "userProgressMessage";

    private static final Map<GenerationEventType, UserProgressStage> DOMAIN_STAGE_MAPPINGS =
            domainStageMappings();
    private static final Map<String, UserProgressStage> INTERNAL_STAGE_MAPPINGS =
            internalStageMappings();
    private static final Set<String> APPROVAL_PUBLIC_FIELDS = Set.of(
            "taskId", "action", "approvalId", "request", "oneTime", "expiresAt", "eventId"
    );

    /** 将可观察性领域事件转换成面向用户的阶段；无用户语义的事件不会公开。 */
    public Optional<GenerationStreamEvent> map(GenerationEvent event) {
        if (event == null || event.type() == null) {
            return Optional.empty();
        }
        UserProgressStage stage = DOMAIN_STAGE_MAPPINGS.get(event.type());
        return stage == null ? Optional.empty() : Optional.of(toProgressEvent(stage));
    }

    /**
     * 将运行时流事件转换成公共体验事件。
     *
     * <p>非进度类业务事件保持原协议，由后续公共边界继续做脱敏和大小限制。</p>
     */
    public Optional<GenerationStreamEvent> map(GenerationStreamEvent event) {
        if (event == null) {
            return Optional.empty();
        }
        if (GenerationStreamEvent.AGENT_EVENT.equals(event.getType())) {
            return mapAgentEvent(event);
        }
        if (GenerationStreamEvent.GENERATION_STAGE.equals(event.getType())) {
            return Optional.of(mapGenerationStage(event));
        }
        return Optional.of(event);
    }

    /** 判断事件是否为本模块发布的稳定用户进度。 */
    public boolean isUserProgressEvent(GenerationStreamEvent event) {
        if (event == null || !GenerationStreamEvent.GENERATION_STAGE.equals(event.getType())) {
            return false;
        }
        return USER_AUDIENCE.equals(stringValue(event.getData(), "audience"))
                && CONTRACT_VERSION == intValue(event.getData(), "contractVersion");
    }

    /** 返回稳定用户阶段编码；非用户进度事件返回空字符串。 */
    public String userProgressStageCode(GenerationStreamEvent event) {
        return isUserProgressEvent(event) ? stringValue(event.getData(), "stage") : "";
    }

    private Optional<GenerationStreamEvent> mapAgentEvent(GenerationStreamEvent event) {
        String internalStage = normalizedValue(event.getData(), "stage");
        String status = normalizedValue(event.getData(), "status");
        if ("approval".equals(internalStage)) {
            return Optional.of(toPublicApprovalEvent(event, status));
        }
        UserProgressStage stage = resolveInternalStage(internalStage, status);
        return Optional.of(toProgressEvent(stage));
    }

    private GenerationStreamEvent mapGenerationStage(GenerationStreamEvent event) {
        if (isUserProgressEvent(event)) {
            return event;
        }
        String internalStage = normalizedValue(event.getData(), "stage");
        return toProgressEvent(resolveInternalStage(internalStage, ""));
    }

    private UserProgressStage resolveInternalStage(String stage, String status) {
        if ("approval_required".equals(status) || "waiting_approval".equals(status)) {
            return UserProgressStage.AWAITING_APPROVAL;
        }
        UserProgressStage mapped = INTERNAL_STAGE_MAPPINGS.get(stage);
        return mapped == null ? UserProgressStage.IMPLEMENTING : mapped;
    }

    private GenerationStreamEvent toProgressEvent(UserProgressStage stage) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("audience", USER_AUDIENCE);
        data.put("contractVersion", CONTRACT_VERSION);
        data.put("stage", stage.getCode());
        data.put("message", stage.getDefaultMessage());
        data.put("phase", stage.getFrontendPhase());
        data.put("terminal", stage.isTerminal());
        return GenerationStreamEvent.generationStage(stage.getDefaultMessage(), Map.copyOf(data));
    }

    private GenerationStreamEvent toPublicApprovalEvent(GenerationStreamEvent event, String status) {
        UserProgressStage progressStage = "approval_required".equals(status)
                || "waiting_approval".equals(status)
                ? UserProgressStage.AWAITING_APPROVAL
                : UserProgressStage.IMPLEMENTING;
        Map<String, Object> source = event.getData() == null ? Map.of() : event.getData();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("agent", "操作确认");
        data.put("stage", "approval");
        data.put("status", status.isBlank() ? "running" : status);
        String message = approvalSummary(status);
        data.put("summary", message);
        data.put(USER_PROGRESS_STAGE_FIELD, progressStage.getCode());
        data.put(USER_PROGRESS_MESSAGE_FIELD, message);
        for (String field : APPROVAL_PUBLIC_FIELDS) {
            Object value = source.get(field);
            if (value != null) {
                data.put(field, value);
            }
        }
        return GenerationStreamEvent.agentEvent(message, Map.copyOf(data));
    }

    private String approvalSummary(String status) {
        return switch (status) {
            case "approval_required", "waiting_approval" ->
                    "请批准或拒绝本次操作；如不再继续，也可以取消任务";
            case "approval_rejected" -> "你已拒绝本次操作，系统不会执行它，并将尝试安全方案";
            case "approval_approved" -> "你已批准本次操作，系统正在继续执行";
            case "approval_expired" -> "审批已过期，系统不会执行该操作，并将尝试安全方案";
            default -> "正在处理操作确认";
        };
    }

    private static Map<GenerationEventType, UserProgressStage> domainStageMappings() {
        Map<GenerationEventType, UserProgressStage> mappings = new EnumMap<>(GenerationEventType.class);
        mappings.put(GenerationEventType.TASK_ROUTE, UserProgressStage.UNDERSTANDING);
        mappings.put(GenerationEventType.GENERATION_START, UserProgressStage.UNDERSTANDING);
        mappings.put(GenerationEventType.AGENT_EDIT_READ, UserProgressStage.UNDERSTANDING);
        mappings.put(GenerationEventType.AGENT_EDIT_UNDERSTAND, UserProgressStage.UNDERSTANDING);
        mappings.put(GenerationEventType.EDIT_ROUTE, UserProgressStage.PLANNING);
        mappings.put(GenerationEventType.AGENT_EDIT_PLAN, UserProgressStage.PLANNING);
        mappings.put(GenerationEventType.FILE_LOCATOR, UserProgressStage.PLANNING);
        mappings.put(GenerationEventType.PATCH_APPLY, UserProgressStage.IMPLEMENTING);
        mappings.put(GenerationEventType.REPAIR_START, UserProgressStage.IMPLEMENTING);
        mappings.put(GenerationEventType.EDIT_ROLLBACK, UserProgressStage.IMPLEMENTING);
        mappings.put(GenerationEventType.AGENT_EDIT_VERIFY, UserProgressStage.VERIFYING);
        mappings.put(GenerationEventType.VALIDATION_START, UserProgressStage.VERIFYING);
        mappings.put(GenerationEventType.VALIDATION_RESULT, UserProgressStage.VERIFYING);
        mappings.put(GenerationEventType.DEV_SERVER_VALIDATION_RESULT, UserProgressStage.VERIFYING);
        mappings.put(GenerationEventType.INDEX_UPDATE, UserProgressStage.VERIFYING);
        mappings.put(GenerationEventType.FIRST_PREVIEW_READY, UserProgressStage.PREVIEW_READY);
        mappings.put(GenerationEventType.TASK_DONE, UserProgressStage.DELIVERED);
        return Map.copyOf(mappings);
    }

    private static Map<String, UserProgressStage> internalStageMappings() {
        Map<String, UserProgressStage> mappings = new LinkedHashMap<>();
        putAll(mappings, UserProgressStage.UNDERSTANDING,
                "reasoning");
        putAll(mappings, UserProgressStage.PLANNING,
                "dag", "planning", "context", "template", "architecture",
                "create_spec_started", "create_spec_recipe", "recipe_group_started",
                "route_fallback");
        // 仅限「模型/模板确实在写代码」的内部阶段。注意 patch_apply / patch_applied 来自 CREATE
        // 模板物化，属于实现期；而收尾产出的 patch 摘要同名不同义，已归入下方收口期。
        putAll(mappings, UserProgressStage.IMPLEMENTING,
                "codegen", "patch_apply", "patch_applied",
                "rollback", "recipe_default_rendered", "recipe_skeleton_only",
                "create_recipe_unsupported", "create_spec_degraded",
                "create_spec_execution_deferred", "create_spec_group_degraded",
                "create_spec_preview_deadline_degraded", "create_spec_recipe_applied");
        // 代码生成结束之后的收口期：验证、审查、差异摘要与提交。
        //
        // diff / patch / commit 曾被归入实现期，但它们由收尾链路发出，此时代码早已写完。
        // 暂定预览把「已可预览」提前到验证窗口内之后，这个错分会让用户先看到「已可预览」
        // 再看到「正在生成或修改代码」，读起来像生成重启了 —— 是不实陈述，不只是顺序回退。
        putAll(mappings, UserProgressStage.VERIFYING,
                "quality", "buildfix", "pre_write_validation", "codegen_done",
                "model_turn_admission", "validation", "build",
                "orphan_review", "diff", "patch", "commit");
        return Map.copyOf(mappings);
    }

    private static void putAll(Map<String, UserProgressStage> mappings,
                               UserProgressStage stage,
                               String... internalStages) {
        for (String internalStage : internalStages) {
            mappings.put(internalStage, stage);
        }
    }

    private static String normalizedValue(Map<String, Object> data, String key) {
        return stringValue(data, key).trim().toLowerCase(Locale.ROOT);
    }

    private static String stringValue(Map<String, Object> data, String key) {
        if (data == null || data.get(key) == null) {
            return "";
        }
        return String.valueOf(data.get(key));
    }

    private static int intValue(Map<String, Object> data, String key) {
        if (data == null || !(data.get(key) instanceof Number number)) {
            return -1;
        }
        return number.intValue();
    }
}
