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
@Table("ai_release_audit")
public class AiReleaseAuditEntity {

    @Id(keyType = KeyType.Auto)
    private Long id;
    private String auditId;
    private String evidenceId;
    private String subjectType;
    private String subjectKey;
    private String candidateFingerprint;
    private String action;
    private Long operatorUserId;
    private String releaseReference;
    private LocalDateTime createTime;
}
