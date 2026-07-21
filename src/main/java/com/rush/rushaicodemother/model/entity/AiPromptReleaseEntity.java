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
@Table("ai_prompt_release")
public class AiPromptReleaseEntity {

    @Id(keyType = KeyType.None)
    private String promptKey;
    private String stableVersion;
    private String canaryVersion;
    private Integer canaryPercentage;
    private Long revision;
    private Long updatedBy;
    private String changeNote;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
