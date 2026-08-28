package com.rush.rushaicodemother.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** 应用生成控制的 MyBatis 持久化投影。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AppGenerationControlEntity {

    private Long appId;
    private Integer generationPaused;
    private Integer emergencyStopped;
    private Integer maxConcurrentTasks;
    private String modelPolicy;
    private String dependencyMutationPolicy;
    private String dependencyNetworkPolicy;
    private String dangerousToolPolicy;
    private Long monthlyCreditLimit;
    private Long version;
    private Long updatedBy;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
