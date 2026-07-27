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
 * 生成构建日志的持久化实体。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("generation_build_log")
public class GenerationBuildLog implements Serializable {

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

    @Column("projectPath")
    private String projectPath;

    /** 当前阶段。 */
    private String stage;

    /** 是否执行成功。 */
    private Integer success;

    private String summary;

    private String report;

    /** 质量门禁结果。 */
    @Column("qualityGate")
    private String qualityGate;

    @Column("willAutoRepair")
    private Integer willAutoRepair;

    /** 创建时间。 */
    @Column("createTime")
    private LocalDateTime createTime;

    /** 逻辑删除标记。 */
    @Column(value = "isDelete", isLogicDelete = true)
    private Integer isDelete;
}
