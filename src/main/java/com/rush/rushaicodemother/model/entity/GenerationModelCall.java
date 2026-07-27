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

/**
 * 生成模型调用的持久化实体。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("generation_model_call")
public class GenerationModelCall implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键编号。 */
    @Id(keyType = KeyType.Auto)
    private Long id;

    @Column("callId")
    private String callId;

    /** 生成任务编号。 */
    @Column("taskId")
    private String taskId;

    /** 应用编号。 */
    @Column("appId")
    private Long appId;

    /** 用户编号。 */
    @Column("userId")
    private Long userId;

    /** 模型提供方。 */
    private String provider;

    private String model;

    @Column("callStatus")
    private String callStatus;

    @Column("providerRequestId")
    private String providerRequestId;

    @Column("promptTokens")
    private Integer promptTokens;

    @Column("completionTokens")
    private Integer completionTokens;

    /** 总令牌数。 */
    @Column("totalTokens")
    private Integer totalTokens;

    @Column("latencyMs")
    private Long latencyMs;

    @Column("finishReason")
    private String finishReason;

    @Column("usageSource")
    private String usageSource;

    @Column("errorCategory")
    private String errorCategory;

    @Column("requestHash")
    private String requestHash;

    @Column("promptTemplateHash")
    private String promptTemplateHash;

    @Column("toolSchemaHash")
    private String toolSchemaHash;

    @Column("modelConfigHash")
    private String modelConfigHash;

    @Column("requestMessageCount")
    private Integer requestMessageCount;

    @Column("toolCount")
    private Integer toolCount;

    @Column("rawMetadataJson")
    private String rawMetadataJson;

    /** 创建时间。 */
    @Column("createTime")
    private LocalDateTime createTime;

    /** 逻辑删除标记。 */
    @Column(value = "isDelete", isLogicDelete = true)
    private Integer isDelete;
}
