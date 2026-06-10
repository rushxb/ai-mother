package com.rush.rushaicodemother.model.entity;

import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import com.mybatisflex.core.keygen.KeyGenerators;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("generation_model_call")
public class GenerationModelCall implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Id(keyType = KeyType.Generator, value = KeyGenerators.snowFlakeId)
    private Long id;

    @Column("taskId")
    private String taskId;

    @Column("appId")
    private Long appId;

    @Column("userId")
    private Long userId;

    private String provider;

    private String model;

    @Column("promptTokens")
    private Integer promptTokens;

    @Column("completionTokens")
    private Integer completionTokens;

    @Column("totalTokens")
    private Integer totalTokens;

    @Column("latencyMs")
    private Long latencyMs;

    @Column("finishReason")
    private String finishReason;

    @Column("usageSource")
    private String usageSource;

    @Column("rawMetadataJson")
    private String rawMetadataJson;

    @Column("createTime")
    private LocalDateTime createTime;

    @Column(value = "isDelete", isLogicDelete = true)
    private Integer isDelete;
}
