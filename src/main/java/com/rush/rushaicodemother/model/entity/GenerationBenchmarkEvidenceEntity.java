package com.rush.rushaicodemother.model.entity;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 生成基准测试证据持久化实体。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("generation_benchmark_evidence")
public class GenerationBenchmarkEvidenceEntity {

    /** 主键编号。 */
    @Id(keyType = KeyType.Auto)
    private Long id;
    /** 证据编号。 */
    private String evidenceId;
    /** 审计对象类型。 */
    private String subjectType;
    /** 审计对象唯一键。 */
    private String subjectKey;
    /** 候选内容指纹。 */
    private String candidateFingerprint;
    /** 签名版本。 */
    private Integer signatureVersion;
    private Long candidatePhysicalRequestCount;
    /** 数据集指纹。 */
    private String datasetFingerprint;
    /** 评分器指纹。 */
    private String graderFingerprint;
    /** 运行时配置指纹。 */
    private String runtimeConfigFingerprint;
    private String gitCommit;
    /** 模型配置指纹。 */
    private String modelFingerprint;
    /** 提示词包指纹。 */
    private String promptBundleFingerprint;
    private String reportSha256;
    /** 报告 JSON。 */
    private String reportJson;
    private Integer passed;
    private String violationsJson;
    /** 数字签名。 */
    private String signature;
    /** 评估时间。 */
    private LocalDateTime evaluatedAt;
    /** 过期时间。 */
    private LocalDateTime expiresAt;
    /** 创建时间。 */
    private LocalDateTime createTime;
    /** 逻辑删除标记。 */
    private Integer isDelete;
}
