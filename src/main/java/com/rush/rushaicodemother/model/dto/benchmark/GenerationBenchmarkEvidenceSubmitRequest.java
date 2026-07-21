package com.rush.rushaicodemother.model.dto.benchmark;

import com.rush.rushaicodemother.orchestration.benchmark.evidence.GenerationBenchmarkEvidenceSubject;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.Instant;

@Data
public class GenerationBenchmarkEvidenceSubmitRequest {

    @NotNull
    private GenerationBenchmarkEvidenceSubject subjectType;

    @NotBlank
    @Size(max = 128)
    private String subjectKey;

    @NotBlank
    @Pattern(regexp = "[0-9a-fA-F]{64}")
    private String candidateFingerprint;

    @NotBlank
    @Pattern(regexp = "[0-9a-fA-F]{64}")
    private String datasetFingerprint;

    @NotBlank
    @Size(max = 128)
    private String graderFingerprint;

    @NotBlank
    @Pattern(regexp = "[0-9a-fA-F]{64}")
    private String runtimeConfigFingerprint;

    @NotBlank
    @Pattern(regexp = "[0-9a-fA-F]{7,64}")
    private String gitCommit;

    @NotBlank
    @Pattern(regexp = "[0-9a-fA-F]{64}")
    private String modelFingerprint;

    @NotBlank
    @Pattern(regexp = "[0-9a-fA-F]{64}")
    private String promptBundleFingerprint;

    @NotBlank
    private String reportJson;

    @NotNull
    private Instant evaluatedAt;

    @NotNull
    private Instant expiresAt;

    @NotBlank
    @Pattern(regexp = "[0-9a-fA-F]{64}")
    private String signature;
}
