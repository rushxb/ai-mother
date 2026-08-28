package com.rush.rushaicodemother.orchestration.delivery;

import com.rush.rushaicodemother.model.enums.GenerationTaskStatus;
import com.rush.rushaicodemother.orchestration.attempt.completion.GenerationCompletionEvidence;
import com.rush.rushaicodemother.orchestration.attempt.completion.GenerationCompletionEvidenceSet;
import com.rush.rushaicodemother.orchestration.attempt.completion.GenerationCompletionEvidenceType;
import com.rush.rushaicodemother.service.trace.GenerationOutcomeQuality;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerationDeliveryReceiptFactoryTest {

    @Test
    void successfulDeliveryMustExposeOnlyStableObservedFacts() {
        GenerationDeliveryReceipt receipt = GenerationDeliveryReceiptFactory.fromTerminal(
                "lightweight_edit",
                GenerationTaskStatus.SUCCESS,
                GenerationCompletionEvidenceSet.of(
                        GenerationCompletionEvidence.of(
                                GenerationCompletionEvidenceType.WORKSPACE_CHANGE,
                                "edit_pipeline",
                                "已观察到工作区变更"),
                        GenerationCompletionEvidence.of(
                                GenerationCompletionEvidenceType.BUILD_VALIDATION,
                                "build_runner",
                                "构建通过")
                ),
                GenerationOutcomeQuality.ofSuccess(3, 0, true, 1_200L)
        );

        assertEquals(GenerationDeliveryReceipt.CURRENT_SCHEMA_VERSION, receipt.schemaVersion());
        assertEquals("lightweight_edit", receipt.actualRoute());
        assertEquals(3, receipt.changeSummary().changedFileCount());
        assertEquals("passed", receipt.validationSummary().status());
        assertEquals("build", receipt.validationSummary().highestLevel());
        assertEquals(
                java.util.List.of("workspace_change", "build_validation"),
                receipt.validationSummary().evidenceTypes());
        assertEquals("verified", receipt.previewMaturity());
        assertEquals(1_200L, receipt.firstPreviewMillis());
        assertNull(receipt.failureCategory());
        assertFalse(receipt.retryable());
        assertEquals("none", receipt.recoveryAction());
        assertEquals("pending", receipt.costSummary().settlementStatus());
    }

    @Test
    void retryableFailureMustKeepCategoryAndActionAcrossRefresh() {
        GenerationDeliveryReceipt receipt = GenerationDeliveryReceiptFactory.fromTerminal(
                "agent_edit",
                GenerationTaskStatus.FAILED,
                GenerationCompletionEvidenceSet.empty(),
                GenerationOutcomeQuality.ofFailure("model_timeout", 1, 2, 900L)
        );

        assertEquals("model_timeout", receipt.failureCategory());
        assertTrue(receipt.retryable());
        assertEquals("retry", receipt.recoveryAction());
        assertEquals("incomplete", receipt.validationSummary().status());
        assertEquals("provisional", receipt.previewMaturity());
    }

    @Test
    void workspaceEvidenceAloneMustNotBeReportedAsValidationPassed() {
        GenerationDeliveryReceipt receipt = GenerationDeliveryReceiptFactory.fromTerminal(
                "agent_edit",
                GenerationTaskStatus.SUCCESS,
                GenerationCompletionEvidenceSet.of(GenerationCompletionEvidence.of(
                        GenerationCompletionEvidenceType.WORKSPACE_CHANGE,
                        "edit_pipeline",
                        "已观察到工作区变更")),
                GenerationOutcomeQuality.ofSuccess(1, 0, null, null)
        );

        assertEquals("not_observed", receipt.validationSummary().status());
        assertEquals("not_observed", receipt.validationSummary().highestLevel());
    }

    @Test
    void providerTemporaryFailuresMustExposeSpecificRetryActions() {
        GenerationDeliveryReceipt rateLimited = failedReceipt("model_rate_limit");
        GenerationDeliveryReceipt timedOut = failedReceipt("model_timeout");
        GenerationDeliveryReceipt unavailable = failedReceipt("model_unavailable");

        assertEquals("retry_later", rateLimited.recoveryAction());
        assertEquals("模型请求过于频繁，请稍后重新提交任务", rateLimited.nextStep());
        assertEquals("retry", timedOut.recoveryAction());
        assertEquals("模型响应超时，可重试或缩小本次生成范围", timedOut.nextStep());
        assertEquals("retry_later", unavailable.recoveryAction());
        assertEquals("模型服务暂时不可用，请稍后重新提交任务", unavailable.nextStep());
        assertTrue(rateLimited.retryable());
        assertTrue(timedOut.retryable());
        assertTrue(unavailable.retryable());
    }

    @Test
    void workspaceUnknownFailureMustForbidAutomaticRetryAndGiveReconciliationAction() {
        GenerationDeliveryReceipt receipt = failedReceipt("workspace_result_unknown");

        assertFalse(receipt.retryable());
        assertEquals("reconcile_workspace", receipt.recoveryAction());
        assertEquals("请刷新并核对当前文件与保留目录；确认实际结果前请勿重试或回滚",
                receipt.nextStep());
    }

    @Test
    void cancellationAndDeadlineMustExposeDifferentRecoveryGuidance() {
        GenerationDeliveryReceipt cancelled = terminalReceipt(GenerationTaskStatus.CANCELLED);
        GenerationDeliveryReceipt deadline = terminalReceipt(GenerationTaskStatus.DEADLINE_EXCEEDED);

        assertEquals("resubmit", cancelled.recoveryAction());
        assertEquals("任务已取消；如仍需生成，请重新提交任务", cancelled.nextStep());
        assertEquals("retry", deadline.recoveryAction());
        assertEquals("任务超过截止时间；可稍后重试或缩小本次生成范围", deadline.nextStep());
    }

    private GenerationDeliveryReceipt failedReceipt(String category) {
        return GenerationDeliveryReceiptFactory.fromTerminal(
                "agent_edit",
                GenerationTaskStatus.FAILED,
                GenerationCompletionEvidenceSet.empty(),
                GenerationOutcomeQuality.ofFailure(category, 0, 0, null)
        );
    }

    private GenerationDeliveryReceipt terminalReceipt(GenerationTaskStatus status) {
        return GenerationDeliveryReceiptFactory.fromTerminal(
                "agent_edit",
                status,
                GenerationCompletionEvidenceSet.empty(),
                GenerationOutcomeQuality.empty()
        );
    }
}
