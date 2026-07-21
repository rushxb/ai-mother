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
@Table("ai_prompt_release_history")
public class AiPromptReleaseHistoryEntity {

    @Id(keyType = KeyType.None)
    private Long revision;
    private String promptKey;
    private String stableVersion;
    private String canaryVersion;
    private Integer canaryPercentage;
    private String action;
    private Long sourceRevision;
    private Long updatedBy;
    private String changeNote;
    private String evidenceId;
    private LocalDateTime createTime;
}
