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
 * AI 提示词发布历史持久化实体。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("ai_prompt_release_history")
public class AiPromptReleaseHistoryEntity {

    /** 修订版本。 */
    @Id(keyType = KeyType.None)
    private Long revision;
    /** 提示词唯一键。 */
    private String promptKey;
    /** 稳定版本。 */
    private String stableVersion;
    /** 灰度版本。 */
    private String canaryVersion;
    /** 灰度发布比例。 */
    private Integer canaryPercentage;
    /** 执行动作。 */
    private String action;
    private Long sourceRevision;
    /** 更新人编号。 */
    private Long updatedBy;
    /** 变更说明。 */
    private String changeNote;
    /** 证据编号。 */
    private String evidenceId;
    /** 创建时间。 */
    private LocalDateTime createTime;
}
