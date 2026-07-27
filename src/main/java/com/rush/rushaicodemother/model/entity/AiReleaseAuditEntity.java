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
 * AI 发布审计持久化实体。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("ai_release_audit")
public class AiReleaseAuditEntity {

    /** 主键编号。 */
    @Id(keyType = KeyType.Auto)
    private Long id;
    private String auditId;
    /** 证据编号。 */
    private String evidenceId;
    /** 审计对象类型。 */
    private String subjectType;
    /** 审计对象唯一键。 */
    private String subjectKey;
    /** 候选内容指纹。 */
    private String candidateFingerprint;
    /** 执行动作。 */
    private String action;
    private Long operatorUserId;
    private String releaseReference;
    /** 创建时间。 */
    private LocalDateTime createTime;
}
