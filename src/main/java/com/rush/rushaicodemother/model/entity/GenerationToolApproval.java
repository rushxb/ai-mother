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

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("generation_tool_approval")
public class GenerationToolApproval {
    @Id(keyType = KeyType.Auto)
    private Long id;
    @Column("approvalId")
    private String approvalId;
    @Column("taskId")
    private String taskId;
    @Column("appId")
    private Long appId;
    @Column("userId")
    private Long userId;
    private String action;
    @Column("requestJson")
    private String requestJson;
    private String status;
    @Column("requestedAt")
    private LocalDateTime requestedAt;
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
    private Long version;
}
