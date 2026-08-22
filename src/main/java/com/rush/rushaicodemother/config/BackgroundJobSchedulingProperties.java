package com.rush.rushaicodemother.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** 后台周期任务线程池容量配置。 */
@Data
@Validated
@ConfigurationProperties(prefix = "app.background-jobs.scheduling")
public class BackgroundJobSchedulingProperties {

    public static final int DEFAULT_POOL_SIZE = 8;
    public static final int GENERATION_TASK_MAINTENANCE_POOL_SIZE = 2;

    /** 未指定 scheduler 的普通后台任务线程数。 */
    @Min(1)
    @Max(64)
    private int defaultPoolSize = DEFAULT_POOL_SIZE;

    /** 生成任务心跳与恢复专用线程数；至少为 2 才能保证两者并发。 */
    @Min(2)
    @Max(16)
    private int generationTaskMaintenancePoolSize = GENERATION_TASK_MAINTENANCE_POOL_SIZE;
}
