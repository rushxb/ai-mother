package com.rush.rushaicodemother.orchestration.benchmark.worker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rush.rushaicodemother.config.GenerationBenchmarkWorkerProperties;
import com.rush.rushaicodemother.orchestration.benchmark.evidence.GenerationBenchmarkEvidenceSubject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class GenerationBenchmarkWorkerResultWriterTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void writerMustAtomicallyReplaceCompleteJsonResult() throws Exception {
        Path output = temporaryDirectory.resolve("nested/benchmark-result.json");
        GenerationBenchmarkWorkerProperties properties =
                new GenerationBenchmarkWorkerProperties();
        properties.setOutputFile(output.toString());
        ObjectMapper objectMapper = new ObjectMapper();
        GenerationBenchmarkWorkerResultWriter writer =
                new GenerationBenchmarkWorkerResultWriter(properties, objectMapper);
        String fingerprint = "a".repeat(64);
        GenerationBenchmarkWorkerResult firstResult = new GenerationBenchmarkWorkerResult(
                GenerationBenchmarkWorkerResult.CURRENT_SCHEMA_VERSION,
                GenerationBenchmarkWorkerResult.Status.PASSED,
                GenerationBenchmarkEvidenceSubject.AI_MODEL_ENABLE,
                "7",
                fingerprint,
                1L,
                "evidence-1",
                List.of(),
                GenerationBenchmarkWorkerTestFixtures.report(fingerprint, fingerprint)
        );
        GenerationBenchmarkWorkerResult replacementResult = new GenerationBenchmarkWorkerResult(
                GenerationBenchmarkWorkerResult.CURRENT_SCHEMA_VERSION,
                GenerationBenchmarkWorkerResult.Status.PASSED,
                GenerationBenchmarkEvidenceSubject.AI_MODEL_ENABLE,
                "7",
                fingerprint,
                1L,
                "evidence-2",
                List.of(),
                GenerationBenchmarkWorkerTestFixtures.report(fingerprint, fingerprint)
        );

        writer.prepare();
        writer.write(firstResult);
        writer.write(replacementResult);

        JsonNode payload = objectMapper.readTree(output.toFile());
        assertEquals(GenerationBenchmarkWorkerResult.CURRENT_SCHEMA_VERSION,
                payload.path("schemaVersion").asInt());
        assertEquals("PASSED", payload.path("status").asText());
        assertEquals("evidence-2", payload.path("evidenceId").asText());
        assertEquals(1L, payload.path("candidatePhysicalRequestCount").asLong());
        try (var files = Files.list(output.getParent())) {
            assertFalse(files.anyMatch(path -> path.getFileName().toString().endsWith(".tmp")));
        }
    }
}
