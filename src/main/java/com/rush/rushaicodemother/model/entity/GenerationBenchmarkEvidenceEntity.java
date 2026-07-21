package com.rush.rushaicodemother.model.entity;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("generation_benchmark_evidence")
public class GenerationBenchmarkEvidenceEntity {

    @Id(keyType = KeyType.Auto)
    private Long id;
    private String evidenceId;
    private String subjectType;
    private String subjectKey;
    private String candidateFingerprint;
    private String datasetFingerprint;
    private String graderFingerprint;
    private String runtimeConfigFingerprint;
    private String gitCommit;
    private String modelFingerprint;
    private String promptBundleFingerprint;
    private String reportSha256;
    private String reportJson;
    private Integer passed;
    private String violationsJson;
    private String signature;
    private LocalDateTime evaluatedAt;
    private LocalDateTime expiresAt;
    private LocalDateTime createTime;
    private Integer isDelete;
}
