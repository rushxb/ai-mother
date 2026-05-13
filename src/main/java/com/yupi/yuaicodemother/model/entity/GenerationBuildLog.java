package com.yupi.yuaicodemother.model.entity;

import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import com.mybatisflex.core.keygen.KeyGenerators;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("generation_build_log")
public class GenerationBuildLog implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Id(keyType = KeyType.Generator, value = KeyGenerators.snowFlakeId)
    private Long id;

    @Column("taskId")
    private String taskId;

    @Column("appId")
    private Long appId;

    @Column("userId")
    private Long userId;

    @Column("projectPath")
    private String projectPath;

    private String stage;

    private Integer success;

    private String summary;

    private String report;

    @Column("qualityGate")
    private String qualityGate;

    @Column("willAutoRepair")
    private Integer willAutoRepair;

    @Column("createTime")
    private LocalDateTime createTime;

    @Column(value = "isDelete", isLogicDelete = true)
    private Integer isDelete;
}
