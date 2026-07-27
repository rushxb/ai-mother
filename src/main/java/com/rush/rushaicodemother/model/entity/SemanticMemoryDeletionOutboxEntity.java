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
 * 语义记忆删除事务发件箱持久化实体。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("semantic_memory_deletion_outbox")
public class SemanticMemoryDeletionOutboxEntity {

    /** 主键编号。 */
    @Id(keyType = KeyType.Auto)
    private Long id;

    @Column("operationId")
    private String operationId;

    @Column("operationType")
    private String operationType;

    /** 租户编号。 */
    @Column("tenantId")
    private Long tenantId;

    /** 应用编号。 */
    @Column("appId")
    private Long appId;

    @Column("requestedByUserId")
    private Long requestedByUserId;

    private Integer attempts;

    @Column("nextAttemptAt")
    private LocalDateTime nextAttemptAt;

    /** 租约持有者。 */
    @Column("leaseOwner")
    private String leaseOwner;

    /** 租约截止时间。 */
    @Column("leaseUntil")
    private LocalDateTime leaseUntil;

    @Column("lastError")
    private String lastError;

    @Column("completedAt")
    private LocalDateTime completedAt;

    /** 创建时间。 */
    @Column("createTime")
    private LocalDateTime createTime;

    /** 更新时间。 */
    @Column("updateTime")
    private LocalDateTime updateTime;
}
