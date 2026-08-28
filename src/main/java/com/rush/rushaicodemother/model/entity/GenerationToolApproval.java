package com.rush.rushaicodemother.model.entity;

import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 生成工具审批的持久化实体。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("generation_tool_approval")
public class GenerationToolApproval {
    /** 主键编号。 */
    @Id(keyType = KeyType.Auto)
    private Long id;
    /** 审批编号。 */
    @Column("approvalId")
    private String approvalId;
    /** 生成任务编号。 */
    @Column("taskId")
    private String taskId;
    /** 发起审批时的持久执行纪元；0 表示迁移前无法证明身份的历史记录。 */
    @Column("requestExecutionEpoch")
    private Long requestExecutionEpoch;
    /** 应用编号。 */
    @Column("appId")
    private Long appId;
    /** 用户编号。 */
    @Column("userId")
    private Long userId;
    /** 执行动作。 */
    private String action;
    @Column("requestJson")
    private String requestJson;
    /** 当前状态。 */
    private String status;
    @Column("requestedAt")
    private LocalDateTime requestedAt;
    /** 过期时间。 */
    @Column("expiresAt")
    private LocalDateTime expiresAt;
    @Column("decidedBy")
    private Long decidedBy;
    @Column("decidedAt")
    private LocalDateTime decidedAt;
    @Column("consumedAt")
    private LocalDateTime consumedAt;
    @Column("executionStartedAt")
    private LocalDateTime executionStartedAt;
    @Column("executionResult")
    private String executionResult;
    @Column("executionAttempt")
    private Integer executionAttempt;
    @Column("toolRequestId")
    private String toolRequestId;
    @Column("toolName")
    private String toolName;
    @Column("argumentsDigest")
    private String argumentsDigest;
    @Column("checkpointJson")
    private String checkpointJson;
    /** 版本号。 */
    private Long version;
}
