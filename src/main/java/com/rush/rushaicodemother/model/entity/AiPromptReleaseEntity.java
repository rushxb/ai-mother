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
 * AI 提示词发布持久化实体。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("ai_prompt_release")
public class AiPromptReleaseEntity {

    /** 提示词唯一键。 */
    @Id(keyType = KeyType.None)
    private String promptKey;
    /** 稳定版本。 */
    private String stableVersion;
    /** 灰度版本。 */
    private String canaryVersion;
    /** 灰度发布比例。 */
    private Integer canaryPercentage;
    /** 修订版本。 */
    private Long revision;
    /** 更新人编号。 */
    private Long updatedBy;
    /** 变更说明。 */
    private String changeNote;
    /** 创建时间。 */
    private LocalDateTime createTime;
    /** 更新时间。 */
    private LocalDateTime updateTime;
}
