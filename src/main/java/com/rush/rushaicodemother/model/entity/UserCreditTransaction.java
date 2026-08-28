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
 * 用户额度交易的持久化实体。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("user_credit_transaction")
public class UserCreditTransaction implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键编号。 */
    @Id(keyType = KeyType.Auto)
    private Long id;

    /** 用户编号。 */
    @Column("userId")
    private Long userId;

    @Column("tenantId")
    private Long tenantId;

    /** 生成任务所属应用；非生成与无法归属的历史流水为空。 */
    @Column("appId")
    private Long appId;

    /** 额度变更量。 */
    @Column("changeAmount")
    private Long changeAmount;

    @Column("balanceAfter")
    private Long balanceAfter;

    private String type;

    @Column("bizId")
    private String bizId;

    /** 备注。 */
    private String remark;

    @Column("adminUserId")
    private Long adminUserId;

    @Column("tokenCount")
    private Long tokenCount;

    /** 创建时间。 */
    @Column(value = "createTime", onInsertValue = "now()")
    private LocalDateTime createTime;

    /** 逻辑删除标记。 */
    @Column(value = "isDelete", isLogicDelete = true)
    private Integer isDelete;
}
