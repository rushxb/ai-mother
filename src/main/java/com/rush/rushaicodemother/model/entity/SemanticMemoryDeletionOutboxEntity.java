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
@Table("semantic_memory_deletion_outbox")
public class SemanticMemoryDeletionOutboxEntity {

    @Id(keyType = KeyType.Auto)
    private Long id;

    @Column("operationId")
    private String operationId;

    @Column("operationType")
    private String operationType;

    @Column("tenantId")
    private Long tenantId;

    @Column("appId")
    private Long appId;

    @Column("requestedByUserId")
    private Long requestedByUserId;

    private Integer attempts;

    @Column("nextAttemptAt")
    private LocalDateTime nextAttemptAt;

    @Column("leaseOwner")
    private String leaseOwner;

    @Column("leaseUntil")
    private LocalDateTime leaseUntil;

    @Column("lastError")
    private String lastError;

    @Column("completedAt")
    private LocalDateTime completedAt;

    @Column("createTime")
    private LocalDateTime createTime;

    @Column("updateTime")
    private LocalDateTime updateTime;
}
