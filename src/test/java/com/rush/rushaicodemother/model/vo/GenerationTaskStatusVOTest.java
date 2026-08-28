package com.rush.rushaicodemother.model.vo;

import com.rush.rushaicodemother.model.enums.GenerationTaskStatus;
import com.rush.rushaicodemother.orchestration.attempt.completion.GenerationCompletionEvidenceSet;
import com.rush.rushaicodemother.orchestration.delivery.GenerationDeliveryReceipt;
import com.rush.rushaicodemother.orchestration.delivery.GenerationDeliveryReceiptFactory;
import com.rush.rushaicodemother.orchestration.delivery.GenerationCostSummary;
import com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskSnapshot;
import com.rush.rushaicodemother.service.trace.GenerationOutcomeQuality;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerationTaskStatusVOTest {

    @Test
    void versionThreeContractMustExposeStructuredRecoveryAndReceiptFields() {
        GenerationDeliveryReceipt receipt = GenerationDeliveryReceiptFactory.fromTerminal(
                "agent_edit", GenerationTaskStatus.FAILED,
                GenerationCompletionEvidenceSet.empty(),
                GenerationOutcomeQuality.ofFailure("dependency", 1, 0, null));
        Instant submittedAt = Instant.parse("2026-08-28T01:00:00Z");
        GenerationTaskSnapshot snapshot = new GenerationTaskSnapshot(
                "task-status", 1L, 2L, "agent_edit", "failed",
                "completed", null, submittedAt, submittedAt.plusSeconds(600),
                false, null, Map.of(), Map.of(), null, receipt);

        GenerationTaskStatusVO view = GenerationTaskStatusVO.from(snapshot);

        assertEquals(GenerationTaskStatusVO.CURRENT_CONTRACT_VERSION, view.contractVersion());
        assertEquals("dependency", view.failureCategory());
        assertTrue(view.retryable());
        assertEquals("check_dependencies", view.recoveryAction());
        assertSame(receipt.validationSummary(), view.validationSummary());
        assertSame(receipt, view.deliveryReceipt());
        assertSame(receipt.costSummary(), view.costSummary());
        assertEquals("dependency", view.guidance().code());
        assertEquals("请检查依赖源与依赖声明后重试", view.guidance().message());
        assertEquals("check_dependencies", view.guidance().action());
        assertTrue(view.guidance().retryable());
    }

    @Test
    void waitingApprovalMustExposeDecisionOptionsWithoutLeakingInternalStageMessage() {
        GenerationTaskStatusVO view = GenerationTaskStatusVO.from(snapshot(
                "waiting_approval", "approval_required:snapshot_delete", false, null));

        assertEquals("approval_waiting", view.guidance().code());
        assertEquals("请批准或拒绝待确认操作；如不再继续，也可以取消任务",
                view.guidance().message());
        assertEquals("review_approval", view.guidance().action());
        assertFalse(view.guidance().retryable());
    }

    @Test
    void pendingCancellationMustTakePrecedenceOverApprovalGuidance() {
        GenerationTaskStatusVO view = GenerationTaskStatusVO.from(snapshot(
                "waiting_approval", "approval_required:snapshot_delete", true, "user_requested"));

        assertEquals("cancellation_pending", view.guidance().code());
        assertEquals("取消请求已记录，请等待任务停止；请勿重复提交相同任务",
                view.guidance().message());
        assertEquals("wait", view.guidance().action());
        assertFalse(view.guidance().retryable());
    }

    @Test
    void approvalDispatchRetryMustTellUserDecisionIsDurablyRecorded() {
        GenerationTaskStatusVO view = GenerationTaskStatusVO.from(snapshot(
                "waiting_approval", "approval_dispatch_retry", false, null));

        assertEquals("approval_resume_pending", view.guidance().code());
        assertEquals("审批决定已记录，系统正在重试恢复执行；可继续等待或取消任务",
                view.guidance().message());
        assertEquals("wait_or_cancel", view.guidance().action());
    }

    @Test
    void runningStatusMustExposeCostWithoutRequiringATerminalReceipt() {
        Instant submittedAt = Instant.parse("2026-08-28T01:00:00Z");
        GenerationCostSummary costSummary = new GenerationCostSummary(
                "reserved", null, null, null,
                5L, 140_000L, 2L, null, null,
                20_000L, "provider_timeout", "已冻结 5 积分，当前暂估消耗 2 积分");
        GenerationTaskSnapshot snapshot = new GenerationTaskSnapshot(
                "task-cost", 1L, 2L, "agent_edit", "running",
                "generating", null, submittedAt, submittedAt.plusSeconds(600),
                false, null, Map.of(), Map.of(), null, null, costSummary);

        GenerationTaskStatusVO view = GenerationTaskStatusVO.from(snapshot);

        assertSame(costSummary, view.costSummary());
        assertEquals(5L, view.costSummary().maximumReservedCredit());
        assertEquals(2L, view.costSummary().provisionalCreditCost());
    }

    private GenerationTaskSnapshot snapshot(String status,
                                              String stageMessage,
                                              boolean cancellationRequested,
                                              String cancellationReason) {
        Instant submittedAt = Instant.parse("2026-08-28T01:00:00Z");
        return new GenerationTaskSnapshot(
                "task-status", 1L, 2L, "agent_edit", status,
                "approval", stageMessage, submittedAt, submittedAt.plusSeconds(600),
                cancellationRequested, cancellationReason, Map.of(), Map.of(), null, null);
    }
}
