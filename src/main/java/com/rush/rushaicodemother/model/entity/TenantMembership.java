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
 * 租户成员关系的持久化实体。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("tenant_membership")
public class TenantMembership implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键编号。 */
    @Id(keyType = KeyType.Auto)
    private Long id;

    /** 租户编号。 */
    @Column("tenantId")
    private Long tenantId;

    /** 用户编号。 */
    @Column("userId")
    private Long userId;

    private String role;

    /** 当前状态。 */
    private String status;

    @Column("joinedAt")
    private LocalDateTime joinedAt;

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
