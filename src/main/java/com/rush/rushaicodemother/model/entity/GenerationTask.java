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
@Table("generation_task")
public class GenerationTask implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Id(keyType = KeyType.Auto)
    private Long id;

    @Column("taskId")
    private String taskId;

    @Column("appId")
    private Long appId;

    @Column("userId")
    private Long userId;

    @Column("tenantId")
    private Long tenantId;

    @Column("idempotencyKeyHash")
    private String idempotencyKeyHash;

    @Column("requestFingerprint")
    private String requestFingerprint;

    @Column("originalCodeGenType")
    private String originalCodeGenType;

    @Column("targetCodeGenType")
    private String targetCodeGenType;

    private String status;

    private String stage;

    @Column("stageMessage")
    private String stageMessage;

    @Column("userPrompt")
    private String userPrompt;

    @Column("enhancedPrompt")
    private String enhancedPrompt;

    @Column("requiresBuildValidation")
    private Integer requiresBuildValidation;

    @Column("qualityGate")
    private String qualityGate;

    @Column("orchestrationMode")
    private String orchestrationMode;

    private String route;

    @Column("runtimeSchemaVersion")
    private Integer runtimeSchemaVersion;

    @Column("runtimePayloadJson")
    private String runtimePayloadJson;

    @Column("dispatchAt")
    private LocalDateTime dispatchAt;

    @Column("dispatchAttempt")
    private Integer dispatchAttempt;

    @Column("dispatchError")
    private String dispatchError;

    @Column("submittedAt")
    private LocalDateTime submittedAt;

    @Column("deadlineAt")
    private LocalDateTime deadlineAt;

    @Column("cancellationRequested")
    private Integer cancellationRequested;

    @Column("cancellationReason")
    private String cancellationReason;

    @Column("leaseOwner")
    private String leaseOwner;

    @Column("leaseUntil")
    private LocalDateTime leaseUntil;

    @Column("heartbeatAt")
    private LocalDateTime heartbeatAt;

    @Column("executionEpoch")
    private Long executionEpoch;

    private Integer attempt;

    private Long version;

    @Column("startTime")
    private LocalDateTime startTime;

    @Column("endTime")
    private LocalDateTime endTime;

    @Column("durationMs")
    private Long durationMs;

    @Column("errorMessage")
    private String errorMessage;

    @Column("memorySummary")
    private String memorySummary;

    @Column("memoryIndexedAt")
    private LocalDateTime memoryIndexedAt;

    @Column("memoryIndexAttempts")
    private Integer memoryIndexAttempts;

    @Column("memoryIndexError")
    private String memoryIndexError;

    @Column("memoryIndexNextAttemptAt")
    private LocalDateTime memoryIndexNextAttemptAt;

    @Column("memoryIndexLeaseOwner")
    private String memoryIndexLeaseOwner;

    @Column("memoryIndexLeaseUntil")
    private LocalDateTime memoryIndexLeaseUntil;

    @Column("totalTokens")
    private Long totalTokens;

    @Column("creditCost")
    private Long creditCost;

    @Column("creditCharged")
    private Integer creditCharged;

    @Column("publicationStatus")
    private String publicationStatus;

    @Column("publicationCodeGenType")
    private String publicationCodeGenType;

    @Column("publicationExecutionEpoch")
    private Long publicationExecutionEpoch;

    @Column("publicationPublishedAt")
    private LocalDateTime publicationPublishedAt;

    @Column("publicationAttempts")
    private Integer publicationAttempts;

    @Column("publicationVersion")
    private Long publicationVersion;

    @Column("publicationError")
    private String publicationError;

    @Column("publicationReconcileAfter")
    private LocalDateTime publicationReconcileAfter;

    @Column("publicationCommittedAt")
    private LocalDateTime publicationCommittedAt;

    @Column("createTime")
    private LocalDateTime createTime;

    @Column("updateTime")
    private LocalDateTime updateTime;

    @Column(value = "isDelete", isLogicDelete = true)
    private Integer isDelete;
}
