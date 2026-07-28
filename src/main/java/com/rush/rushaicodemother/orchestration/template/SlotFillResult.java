package com.rush.rushaicodemother.orchestration.template;

import com.rush.rushaicodemother.orchestration.patch.PatchOperation;

import java.util.List;
import java.util.Map;

/**
 * Slot 填充结果。
 *
 * @param templateId          使用的模板 ID
 * @param filledSlots         已填充的 slot 列表
 * @param patchOperations     生成的 patch 操作列表
 * @param summary             填充摘要
 * @param totalChars          总字符数
 * @param skippedSlots        跳过的 slot 列表（用户需求未涉及）
 * @param metadata            额外元数据
 */
public record SlotFillResult(
        String templateId,
        List<String> filledSlots,
        List<PatchOperation> patchOperations,
        String summary,
        int totalChars,
        List<String> skippedSlots,
        Map<String, Object> metadata
) {
    /**
     * 创建成功的填充结果。
     */
    public static SlotFillResult success(String templateId,
                                          List<String> filledSlots,
                                          List<PatchOperation> patchOperations,
                                          String summary,
                                          int totalChars) {
        return new SlotFillResult(templateId, filledSlots, patchOperations, summary, totalChars,
                List.of(), Map.of());
    }

    /**
     * 创建部分成功的填充结果（有些 slot 被跳过）。
     */
    public static SlotFillResult partial(String templateId,
                                          List<String> filledSlots,
                                          List<PatchOperation> patchOperations,
                                          String summary,
                                          int totalChars,
                                          List<String> skippedSlots) {
        return new SlotFillResult(templateId, filledSlots, patchOperations, summary, totalChars,
                skippedSlots, Map.of());
    }

    /**
     * 是否所有必需的 slot 都已填充。
     */
    public boolean allRequiredSlotsFilled() {
        return skippedSlots == null || skippedSlots.isEmpty();
    }

    /**
     * 获取填充的 slot 数量。
     */
    public int filledSlotCount() {
        return filledSlots != null ? filledSlots.size() : 0;
    }

    /**
     * 获取 patch 操作数量。
     */
    public int patchOperationCount() {
        return patchOperations != null ? patchOperations.size() : 0;
    }

    /**
 * 返回回退。
 *
 * @return 满足条件时返回 {@code true}，否则返回 {@code false}
 */
    public boolean fallback() {
        Object telemetry = telemetry();
        if (!(telemetry instanceof Map<?, ?> telemetryMap)) {
            return false;
        }
        return Boolean.TRUE.equals(telemetryMap.get("fallback"));
    }

    /**
 * 返回回退原因。
 *
 * @return 处理后的插槽填充结果文本
 */
    public String fallbackReason() {
        Object telemetry = telemetry();
        if (!(telemetry instanceof Map<?, ?> telemetryMap)) {
            return "";
        }
        Object reason = telemetryMap.get("fallbackReason");
        return reason == null ? "" : String.valueOf(reason);
    }

    /**
 * 返回遥测。
 *
 * @return 插槽填充结果
 */
    public Object telemetry() {
        if (metadata == null) {
            return Map.of();
        }
        return metadata.getOrDefault("telemetry", Map.of());
    }
}
