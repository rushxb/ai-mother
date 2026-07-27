package com.rush.rushaicodemother.orchestration.benchmark.evidence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkCatalog;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkReport;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkRunner;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GenerationBenchmarkEvidenceCodecTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final GenerationBenchmarkEvidenceCodec codec =
            new GenerationBenchmarkEvidenceCodec(objectMapper);

    @Test
    void currentReportSchemaMustRoundTripWithoutChangingEvidence() {
        GenerationBenchmarkReport report = new GenerationBenchmarkRunner(
                new GenerationBenchmarkCatalog(objectMapper)).summarize(List.of());

        assertEquals(report, codec.parseReport(codec.serializeReport(report)));
    }

    @Test
    void reportWithoutCurrentSchemaMustBeRejectedAtDecodeBoundary() {
        assertThrows(BusinessException.class, () -> codec.parseReport("{}"));
    }
}
