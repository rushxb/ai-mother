package com.rush.rushaicodemother.orchestration.benchmark.evidence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkReport;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Keeps the signed report byte representation separate from its parsed assessment model. */
@Component
@RequiredArgsConstructor
public class GenerationBenchmarkEvidenceCodec {

    private final ObjectMapper objectMapper;

    public GenerationBenchmarkReport parseReport(String reportJson) {
        try {
            return objectMapper.readValue(reportJson, GenerationBenchmarkReport.class);
        } catch (JsonProcessingException malformed) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR,
                    "Benchmark evidence report JSON is invalid", malformed);
        }
    }

    public String reportSha256(String reportJson) {
        return ReleaseCandidateFingerprint.sha256(reportJson);
    }
}
