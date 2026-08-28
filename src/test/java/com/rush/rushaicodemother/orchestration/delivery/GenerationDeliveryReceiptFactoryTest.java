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
}
