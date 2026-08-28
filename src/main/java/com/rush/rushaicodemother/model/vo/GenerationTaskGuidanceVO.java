package com.rush.rushaicodemother.model.vo;

import com.rush.rushaicodemother.orchestration.delivery.GenerationDeliveryReceipt;
import com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskSnapshot;

import java.util.Locale;

/** 面向任务查询客户端的稳定、可行动中文指引。 */
public record GenerationTaskGuidanceVO(
        String code,
        String message,
        String action,
        boolean retryable
) {

    /** 根据持久任务事实生成指引；正常运行且无需用户动作时不返回提示。 */
    public static GenerationTaskGuidanceVO from(GenerationTaskSnapshot snapshot) {
        if (snapshot == null) {
            return null;
        }
        String status = normalize(snapshot.status());
        GenerationDeliveryReceipt receipt = snapshot.deliveryReceipt();
        if ("success".equals(status)) {
            return null;
        }
        if (receipt != null) {
            return fromReceipt(status, receipt);
        }
        if ("cancelled".equals(status)) {
            return new GenerationTaskGuidanceVO(
                    "cancelled", "任务已取消；如仍需生成，请重新提交任务", "resubmit", true);
        }
        if ("deadline_exceeded".equals(status)) {
            return new GenerationTaskGuidanceVO(
                    "deadline_exceeded", "任务超过截止时间；可稍后重试或缩小本次生成范围",
                    "retry", true);
        }
        if (snapshot.cancellationRequested()) {
            return new GenerationTaskGuidanceVO(
                    "cancellation_pending", "取消请求已记录，请等待任务停止；请勿重复提交相同任务",
                    "wait", false);
        }
        if ("waiting_approval".equals(status)) {
            if ("approval_dispatch_retry".equals(normalize(snapshot.stageMessage()))) {
                return new GenerationTaskGuidanceVO(
                        "approval_resume_pending", "审批决定已记录，系统正在重试恢复执行；可继续等待或取消任务",
                        "wait_or_cancel", false);
            }
            return new GenerationTaskGuidanceVO(
                    "approval_waiting", "请批准或拒绝待确认操作；如不再继续，也可以取消任务",
                    "review_approval", false);
        }
        if ("failed".equals(status)) {
            return new GenerationTaskGuidanceVO(
                    "task_failed", "任务执行失败，可稍后重试；重复失败时请联系管理员",
                    "retry", true);
        }
        return null;
    }

    private static GenerationTaskGuidanceVO fromReceipt(
            String status,
            GenerationDeliveryReceipt receipt) {
        String code = receipt.failureCategory();
        if (code == null || code.isBlank()) {
            code = status.isBlank() ? "task_terminal" : status;
        }
        return new GenerationTaskGuidanceVO(
                code,
                receipt.nextStep(),
                receipt.recoveryAction(),
                receipt.retryable());
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
