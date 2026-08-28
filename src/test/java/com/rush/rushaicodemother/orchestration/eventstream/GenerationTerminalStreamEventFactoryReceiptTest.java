package com.rush.rushaicodemother.orchestration.eventstream;

import com.rush.rushaicodemother.core.handler.GenerationStreamEvent;
import com.rush.rushaicodemother.core.handler.GenerationPublicEventSanitizer;
import com.rush.rushaicodemother.model.enums.GenerationTaskStatus;
import com.rush.rushaicodemother.orchestration.attempt.completion.GenerationCompletionEvidenceSet;
import com.rush.rushaicodemother.orchestration.delivery.GenerationDeliveryReceipt;
import com.rush.rushaicodemother.orchestration.delivery.GenerationDeliveryReceiptFactory;
import com.rush.rushaicodemother.service.trace.GenerationOutcomeQuality;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class GenerationTerminalStreamEventFactoryReceiptTest {

    @Test
    void terminalEventMustExposeTheSameStructuredReceiptUsedByDurableFallback() {
        GenerationDeliveryReceipt receipt = GenerationDeliveryReceiptFactory.fromTerminal(
                "heavy_generation",
                GenerationTaskStatus.FAILED,
                GenerationCompletionEvidenceSet.empty(),
                GenerationOutcomeQuality.ofFailure("build", 2, 1, 3_000L)
        );

        GenerationStreamEvent event = GenerationPublicEventSanitizer.sanitize(
                GenerationTerminalStreamEventFactory.create(
                        "task-receipt", GenerationTaskStatus.FAILED, receipt));

        assertEquals("build", event.getData().get("failureCategory"));
        assertEquals(true, event.getData().get("retryable"));
        assertEquals("fix_build", event.getData().get("recoveryAction"));
        java.util.Map<?, ?> projectedReceipt = assertInstanceOf(
                java.util.Map.class, event.getData().get("deliveryReceipt"));
        assertEquals("heavy_generation", projectedReceipt.get("actualRoute"));
        assertEquals("provisional", projectedReceipt.get("previewMaturity"));
        assertInstanceOf(java.util.Map.class, event.getData().get("validationSummary"));
        assertInstanceOf(java.util.Map.class, event.getData().get("costSummary"));
    }
}
