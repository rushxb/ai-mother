package com.rush.rushaicodemother.orchestration.benchmark.evidence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkReport;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 将签名的报告字节表示与其解析的评估模型分开。 */
@Component
@RequiredArgsConstructor
public class GenerationBenchmarkEvidenceCodec {

    private final ObjectMapper objectMapper;

    /**
 * 解析报告。
 *
 * @param reportJson {@code reportJson} 对应的调用参数
 * @return 报告
 */
    public GenerationBenchmarkReport parseReport(String reportJson) {
        try {
            return requireCurrentSchema(
                    objectMapper.readValue(reportJson, GenerationBenchmarkReport.class));
        } catch (JsonProcessingException malformed) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR,
                    "Benchmark 证据报告 JSON 无效", malformed);
        }
    }

    public String serializeReport(GenerationBenchmarkReport report) {
        if (report == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "Benchmark 报告不能为空");
        }
        requireCurrentSchema(report);
        try {
            return objectMapper.writeValueAsString(report);
        } catch (JsonProcessingException failure) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR,
                    "Benchmark 报告无法序列化", failure);
        }
    }

    public String reportSha256(String reportJson) {
        return ReleaseCandidateFingerprint.sha256(reportJson);
    }

    private GenerationBenchmarkReport requireCurrentSchema(GenerationBenchmarkReport report) {
        if (report == null
                || report.schemaVersion() != GenerationBenchmarkReport.CURRENT_SCHEMA_VERSION) {
            throw new BusinessException(
                    ErrorCode.PARAMS_ERROR, "Benchmark 证据报告版本不受支持");
        }
        return report;
    }
}
