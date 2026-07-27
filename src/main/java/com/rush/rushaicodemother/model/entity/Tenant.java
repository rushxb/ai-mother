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
 * 租户的持久化实体。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("tenant")
public class Tenant implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键编号。 */
    @Id(keyType = KeyType.Auto)
    private Long id;

    @Column("tenantKey")
    private String tenantKey;

    @Column("tenantType")
    private String tenantType;

    @Column("displayName")
    private String displayName;

    @Column("ownerUserId")
    private Long ownerUserId;

    /** 当前状态。 */
    private String status;

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
