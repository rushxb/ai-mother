package com.rush.rushaicodemother.model.vo;

import com.rush.rushaicodemother.model.enums.GenerationTaskStatus;
import com.rush.rushaicodemother.orchestration.attempt.completion.GenerationCompletionEvidenceSet;
import com.rush.rushaicodemother.orchestration.delivery.GenerationDeliveryReceipt;
import com.rush.rushaicodemother.orchestration.delivery.GenerationDeliveryReceiptFactory;
import com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskSnapshot;
import com.rush.rushaicodemother.service.trace.GenerationOutcomeQuality;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerationTaskStatusVOTest {

    @Test
    void versionTwoContractMustExposeStructuredRecoveryAndReceiptFields() {
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
    }
}
