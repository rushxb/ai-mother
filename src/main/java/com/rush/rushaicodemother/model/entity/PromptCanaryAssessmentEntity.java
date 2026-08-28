package com.rush.rushaicodemother.model.entity;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** Prompt 灰度门禁的不可变持久证据。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("ai_prompt_canary_assessment")
public class PromptCanaryAssessmentEntity {

    @Id(keyType = KeyType.Auto)
    private Long id;
    private String assessmentId;
    private String promptKey;
    private Long releaseRevision;
    private Long bundleRevision;
    private String bundleId;
    private String stableVersion;
    private String stableContentHash;
    private String canaryVersion;
    private String canaryContentHash;
    private LocalDateTime windowStart;
    private LocalDateTime windowEnd;
    private String decision;
    private Long stableTaskCount;
    private Long canaryTaskCount;
    private Long ambiguousTaskCount;
    private Long invalidAttributionTaskCount;
    private String violationsJson;
    private String evidenceJson;
    private String evidenceHash;
    private LocalDateTime evaluatedAt;
    private LocalDateTime createTime;
}
