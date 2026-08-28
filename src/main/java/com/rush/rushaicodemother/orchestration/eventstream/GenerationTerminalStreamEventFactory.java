package com.rush.rushaicodemother.orchestration.eventstream;

import com.rush.rushaicodemother.core.handler.GenerationStreamEvent;
import com.rush.rushaicodemother.model.enums.GenerationTaskStatus;
import com.rush.rushaicodemother.orchestration.delivery.GenerationDeliveryReceipt;
import com.rush.rushaicodemother.orchestration.delivery.GenerationCostSummary;
import com.rush.rushaicodemother.orchestration.delivery.GenerationValidationSummary;

import java.util.LinkedHashMap;
import java.util.Map;

/** 生成任务终态在实时流、Redis 重放与数据库回退之间共享的稳定公开投影。 */
public final class GenerationTerminalStreamEventFactory {

    private GenerationTerminalStreamEventFactory() {
    }

    public static GenerationStreamEvent create(String taskId, GenerationTaskStatus status) {
        return create(taskId, status, null);
    }

    public static GenerationStreamEvent create(String taskId,
                                               GenerationTaskStatus status,
                                               GenerationDeliveryReceipt receipt) {
        if (taskId == null || taskId.isBlank() || status == null || !status.isTerminal()) {
            throw new IllegalArgumentException("terminal task identity is required");
        }
        String statusValue = status.getValue();
        String outcome = switch (status) {
            case SUCCESS -> "done";
            case CANCELLED -> "cancelled";
            case DEADLINE_EXCEEDED -> "timed_out";
            default -> "failed";
        };
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("taskId", taskId);
        data.put("status", statusValue);
        data.put("outcome", outcome);
        data.put("terminal", true);
        data.put("eventId", taskId + ":" + statusValue + ":durable-terminal");
        if (receipt != null) {
            data.put("failureCategory", receipt.failureCategory());
            data.put("retryable", receipt.retryable());
            data.put("recoveryAction", receipt.recoveryAction());
            data.put("validationSummary", validationSummary(receipt.validationSummary()));
            data.put("deliveryReceipt", deliveryReceipt(receipt));
            data.put("costSummary", costSummary(receipt.costSummary()));
        }
        // LinkedHashMap 允许先装配可空分类；公开前移除 null，避免 Map.copyOf 拒绝空值。
        data.values().removeIf(java.util.Objects::isNull);
        return GenerationStreamEvent.taskTerminal(message(status), Map.copyOf(data));
    }

    /** 显式白名单投影，确保公共事件清理器不会把 record 降级成字符串。 */
    private static Map<String, Object> deliveryReceipt(GenerationDeliveryReceipt receipt) {
        Map<String, Object> projected = new LinkedHashMap<>();
        projected.put("schemaVersion", receipt.schemaVersion());
        projected.put("actualRoute", receipt.actualRoute());
        Map<String, Object> changeSummary = new LinkedHashMap<>();
        changeSummary.put("changedFileCount", receipt.changeSummary().changedFileCount());
        changeSummary.put("summary", receipt.changeSummary().summary());
        removeNullValues(changeSummary);
        projected.put("changeSummary", Map.copyOf(changeSummary));
        projected.put("validationSummary", validationSummary(receipt.validationSummary()));
        projected.put("previewMaturity", receipt.previewMaturity());
        projected.put("firstPreviewMillis", receipt.firstPreviewMillis());
        projected.put("failureCategory", receipt.failureCategory());
        projected.put("retryable", receipt.retryable());
        projected.put("recoveryAction", receipt.recoveryAction());
        projected.put("nextStep", receipt.nextStep());
        projected.put("costSummary", costSummary(receipt.costSummary()));
        removeNullValues(projected);
        return Map.copyOf(projected);
    }

    private static Map<String, Object> validationSummary(GenerationValidationSummary summary) {
        return Map.of(
                "status", summary.status(),
                "highestLevel", summary.highestLevel(),
                "evidenceTypes", summary.evidenceTypes(),
                "summary", summary.summary());
    }

    private static Map<String, Object> costSummary(GenerationCostSummary summary) {
        Map<String, Object> projected = new LinkedHashMap<>();
        projected.put("settlementStatus", summary.settlementStatus());
        projected.put("totalTokens", summary.totalTokens());
        projected.put("creditCost", summary.creditCost());
        projected.put("charged", summary.charged());
        projected.put("summary", summary.summary());
        removeNullValues(projected);
        return Map.copyOf(projected);
    }

    private static void removeNullValues(Map<String, Object> values) {
        values.values().removeIf(java.util.Objects::isNull);
    }

    private static String message(GenerationTaskStatus status) {
        return switch (status) {
            case SUCCESS -> "项目生成完成";
            case CANCELLED -> "项目生成已取消";
            case DEADLINE_EXCEEDED -> "项目生成超时";
            case FAILED -> "项目生成失败";
            default -> "项目生成已结束";
        };
    }
}
