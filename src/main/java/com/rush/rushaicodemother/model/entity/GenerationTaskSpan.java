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
 * 生成任务跨度的持久化实体。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("generation_task_span")
public class GenerationTaskSpan implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键编号。 */
    @Id(keyType = KeyType.Auto)
    private Long id;

    /** 链路跨度编号。 */
    @Column("spanId")
    private String spanId;

    /** 生成任务编号。 */
    @Column("taskId")
    private String taskId;

    /** 当前阶段。 */
    private String stage;

    /** 分类。 */
    private String category;

    /** 当前状态。 */
    private String status;

    /** 实际开始时间。 */
    @Column("startedAt")
    private LocalDateTime startedAt;

    /** 实际结束时间。 */
    @Column("endedAt")
    private LocalDateTime endedAt;

    /** 耗时毫秒数。 */
    @Column("durationMs")
    private Long durationMs;

    /** 详细信息。 */
    private String detail;

    /** 创建时间。 */
    @Column("createTime")
    private LocalDateTime createTime;

    /** 逻辑删除标记。 */
    @Column(value = "isDelete", isLogicDelete = true)
    private Integer isDelete;
}
