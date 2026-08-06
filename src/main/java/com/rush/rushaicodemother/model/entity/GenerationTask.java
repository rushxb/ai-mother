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
 * 生成任务的持久化实体。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("generation_task")
public class GenerationTask implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键编号。 */
    @Id(keyType = KeyType.Auto)
    private Long id;

    /** 生成任务编号。 */
    @Column("taskId")
    private String taskId;

    /** 应用编号。 */
    @Column("appId")
    private Long appId;

    /** 用户编号。 */
    @Column("userId")
    private Long userId;

    /** 租户编号。 */
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

    /** 当前状态。 */
    private String status;

    /** 当前阶段。 */
    private String stage;

    @Column("stageMessage")
    private String stageMessage;

    @Column("userPrompt")
    private String userPrompt;

    @Column("enhancedPrompt")
    private String enhancedPrompt;

    @Column("requiresBuildValidation")
    private Integer requiresBuildValidation;

    /** 质量门禁结果。 */
    @Column("qualityGate")
    private String qualityGate;

    /** 实际使用的思考档位；NULL 表示未采集。 */
    @Column("thinkingMode")
    private String thinkingMode;

    /** 有效变更文件数；NULL 表示未采集。 */
    @Column("changedFileCount")
    private Integer changedFileCount;

    /** 是否免修复通过构建；NULL 表示未采集。 */
    @Column("firstBuildPassed")
    private Integer firstBuildPassed;

    /** 实际修复轮次；NULL 表示未采集。 */
    @Column("repairRounds")
    private Integer repairRounds;

    /** 提交到可预览耗时毫秒；NULL 表示未采集。 */
    @Column("firstPreviewMillis")
    private Long firstPreviewMillis;

    /** 失败分类；NULL 表示未采集。 */
    @Column("failureCategory")
    private String failureCategory;

    /** 交付后被追加改修的时间；NULL 表示未发生或未采集。 */
    @Column("reworkedAt")
    private LocalDateTime reworkedAt;

    /** 经验已蒸馏时间；NULL 且满足条件则待蒸馏。 */
    @Column("distilledAt")
    private LocalDateTime distilledAt;

    @Column("orchestrationMode")
    private String orchestrationMode;

    /** 生成路由。 */
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

    /** 租约持有者。 */
    @Column("leaseOwner")
    private String leaseOwner;

    /** 租约截止时间。 */
    @Column("leaseUntil")
    private LocalDateTime leaseUntil;

    /** 最后心跳时间。 */
    @Column("heartbeatAt")
    private LocalDateTime heartbeatAt;

    /** 执行轮次。 */
    @Column("executionEpoch")
    private Long executionEpoch;

    private Integer attempt;

    /** 版本号。 */
    private Long version;

    /** 开始时间。 */
    @Column("startTime")
    private LocalDateTime startTime;

    /** 结束时间。 */
    @Column("endTime")
    private LocalDateTime endTime;

    /** 耗时毫秒数。 */
    @Column("durationMs")
    private Long durationMs;

    @Column("errorMessage")
    private String errorMessage;

    @Column("memorySummary")
    private String memorySummary;

    @Column("memoryIndexedAt")
    private LocalDateTime memoryIndexedAt;

    @Column("memoryIndexContractVersion")
    private Integer memoryIndexContractVersion;

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

    /** 总令牌数。 */
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

    /** 创建时间。 */
    @Column("createTime")
    private LocalDateTime createTime;

    /** 更新时间。 */
    @Column("updateTime")
    private LocalDateTime updateTime;

    /** 逻辑删除标记。 */
    @Column(value = "isDelete", isLogicDelete = true)
    private Integer isDelete;
}
