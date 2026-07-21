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

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("generation_orchestration_checkpoint")
public class GenerationOrchestrationCheckpoint implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Id(keyType = KeyType.Auto)
    private Long id;

    @Column("taskId")
    private String taskId;

    @Column("appId")
    private Long appId;

    @Column("executionEpoch")
    private Long executionEpoch;

    @Column("requestHash")
    private String requestHash;

    private String status;

    @Column("runtimeState")
    private String runtimeState;

    @Column("currentNode")
    private String currentNode;

    @Column("lastCompletedNode")
    private String lastCompletedNode;

    @Column("checkpointVersion")
    private Long checkpointVersion;

    @Column("payloadJson")
    private String payloadJson;

    @Column("payloadBytes")
    private Integer payloadBytes;

    @Column("createTime")
    private LocalDateTime createTime;

    @Column("updateTime")
    private LocalDateTime updateTime;

    @Column(value = "isDelete", isLogicDelete = true)
    private Integer isDelete;
}
