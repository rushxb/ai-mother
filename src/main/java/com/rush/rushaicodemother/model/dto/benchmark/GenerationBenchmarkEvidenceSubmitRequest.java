package com.rush.rushaicodemother.model.dto.benchmark;

import com.rush.rushaicodemother.orchestration.benchmark.evidence.GenerationBenchmarkEvidenceSubject;
import com.rush.rushaicodemother.orchestration.benchmark.evidence.GenerationBenchmarkEvidenceProtocol;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.Instant;

/**
 * 生成基准测试证据提交请求参数。
 */
@Data
public class GenerationBenchmarkEvidenceSubmitRequest {

    /** 签名版本。 */
    @Min(GenerationBenchmarkEvidenceProtocol.CURRENT_SIGNATURE_VERSION)
    @Max(GenerationBenchmarkEvidenceProtocol.CURRENT_SIGNATURE_VERSION)
    private int signatureVersion;

    /** 审计对象类型。 */
    @NotNull
    private GenerationBenchmarkEvidenceSubject subjectType;

    /** 审计对象唯一键。 */
    @NotBlank
    @Size(max = 128)
    private String subjectKey;

    /** 候选内容指纹。 */
    @NotBlank
    @Pattern(regexp = "[0-9a-fA-F]{64}")
    private String candidateFingerprint;

    @Min(0)
    private long candidatePhysicalRequestCount;

    /** 数据集指纹。 */
    @NotBlank
    @Pattern(regexp = "[0-9a-fA-F]{64}")
    private String datasetFingerprint;

    /** 评分器指纹。 */
    @NotBlank
    @Size(max = 128)
    private String graderFingerprint;

    /** 运行时配置指纹。 */
    @NotBlank
    @Pattern(regexp = "[0-9a-fA-F]{64}")
    private String runtimeConfigFingerprint;

    @NotBlank
    @Pattern(regexp = "(?:[0-9a-fA-F]{40}|[0-9a-fA-F]{64})")
    private String gitCommit;

    /** 模型配置指纹。 */
    @NotBlank
    @Pattern(regexp = "[0-9a-fA-F]{64}")
    private String modelFingerprint;

    /** 提示词包指纹。 */
    @NotBlank
    @Pattern(regexp = "[0-9a-fA-F]{64}")
    private String promptBundleFingerprint;

    /** 报告 JSON。 */
    @NotBlank
    private String reportJson;

    /** 评估时间。 */
    @NotNull
    private Instant evaluatedAt;

    /** 过期时间。 */
    @NotNull
    private Instant expiresAt;

    /** 数字签名。 */
    @NotBlank
    @Pattern(regexp = "[0-9a-fA-F]{64}")
    private String signature;
}
