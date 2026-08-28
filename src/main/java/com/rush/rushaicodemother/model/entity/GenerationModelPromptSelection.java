package com.rush.rushaicodemother.model.entity;

import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/** 模型调用实际使用的 Prompt 版本结构化事实。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("generation_model_prompt_selection")
public class GenerationModelPromptSelection implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Id(keyType = KeyType.Auto)
    private Long id;

    @Column("callId")
    private String callId;

    @Column("taskId")
    private String taskId;

    @Column("promptKey")
    private String promptKey;

    @Column("promptVersion")
    private String promptVersion;

    private String channel;

    @Column("contentHash")
    private String contentHash;

    @Column("bundleId")
    private String bundleId;

    @Column("createTime")
    private LocalDateTime createTime;

    @Column(value = "isDelete", isLogicDelete = true)
    private Integer isDelete;
}
