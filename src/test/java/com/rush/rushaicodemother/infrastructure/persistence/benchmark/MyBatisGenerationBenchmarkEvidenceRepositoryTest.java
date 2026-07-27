package com.rush.rushaicodemother.infrastructure.persistence.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rush.rushaicodemother.mapper.GenerationBenchmarkEvidenceMapper;
import com.rush.rushaicodemother.model.entity.GenerationBenchmarkEvidenceEntity;
import com.rush.rushaicodemother.orchestration.benchmark.evidence.GenerationBenchmarkEvidencePayload;
import com.rush.rushaicodemother.orchestration.benchmark.evidence.GenerationBenchmarkEvidenceProtocol;
import com.rush.rushaicodemother.orchestration.benchmark.evidence.GenerationBenchmarkEvidenceRecord;
import com.rush.rushaicodemother.orchestration.benchmark.evidence.GenerationBenchmarkEvidenceSubject;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MyBatisGenerationBenchmarkEvidenceRepositoryTest {

    @Test
    void immutableEvidenceMustRoundTripWithoutLosingSignedMetadata() {
        GenerationBenchmarkEvidenceMapper mapper = mock(GenerationBenchmarkEvidenceMapper.class);
        MyBatisGenerationBenchmarkEvidenceRepository repository =
                new MyBatisGenerationBenchmarkEvidenceRepository(mapper, new ObjectMapper());
        GenerationBenchmarkEvidenceRecord evidence = evidence();
        when(mapper.insertEvidence(any())).thenReturn(1);

        repository.insert(evidence);

        ArgumentCaptor<GenerationBenchmarkEvidenceEntity> captor =
                ArgumentCaptor.forClass(GenerationBenchmarkEvidenceEntity.class);
        verify(mapper).insertEvidence(captor.capture());
        GenerationBenchmarkEvidenceEntity entity = captor.getValue();
        assertEquals(evidence.evidenceId(), entity.getEvidenceId());
        assertEquals(evidence.payload().candidateFingerprint(), entity.getCandidateFingerprint());
        assertEquals(evidence.payload().signatureVersion(), entity.getSignatureVersion());
        assertEquals(evidence.payload().candidatePhysicalRequestCount(),
                entity.getCandidatePhysicalRequestCount());
        assertEquals(evidence.payload().reportSha256(), entity.getReportSha256());
        assertEquals(evidence.signature(), entity.getSignature());
        assertEquals(1, entity.getPassed());

        when(mapper.selectByEvidenceId(evidence.evidenceId())).thenReturn(entity);
        GenerationBenchmarkEvidenceRecord loaded = repository.findByEvidenceId(evidence.evidenceId()).orElseThrow();

        assertEquals(evidence, loaded);
    }

    private GenerationBenchmarkEvidenceRecord evidence() {
        Instant evaluatedAt = Instant.parse("2026-07-18T00:00:00Z");
        GenerationBenchmarkEvidencePayload payload = new GenerationBenchmarkEvidencePayload(
                GenerationBenchmarkEvidenceProtocol.CURRENT_SIGNATURE_VERSION,
                GenerationBenchmarkEvidenceSubject.AI_MODEL_ENABLE,
                "7",
                "a".repeat(64),
                3L,
                "b".repeat(64),
                "generation-benchmark-graders-v1",
                "c".repeat(64),
                "1234567",
                "d".repeat(64),
                "e".repeat(64),
                "f".repeat(64),
                evaluatedAt,
                evaluatedAt.plusSeconds(3600)
        );
        return new GenerationBenchmarkEvidenceRecord(
                "550e8400-e29b-41d4-a716-446655440000",
                payload,
                "{\"totalTasks\":1}",
                true,
                List.of("informational-note"),
                "1".repeat(64),
                evaluatedAt.plusSeconds(5)
        );
    }
}
