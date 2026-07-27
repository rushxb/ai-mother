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
 * 生成反馈的持久化实体。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("generation_feedback")
public class GenerationFeedback implements Serializable {

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

    /** 评分。 */
    private Integer rating;

    /** 执行结果。 */
    private String outcome;

    /** 评论内容。 */
    private String comment;

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
