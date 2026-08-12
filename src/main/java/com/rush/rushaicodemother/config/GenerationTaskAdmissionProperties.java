package com.rush.rushaicodemother.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/** 生成任务的用户与租户级固定准入边界。 */
@Data
@Component
@Validated
public class GenerationTaskAdmissionProperties {

    public static final int MAX_NON_TERMINAL_TASKS_PER_USER = 4;
    public static final int MAX_NON_TERMINAL_TASKS_PER_TENANT = 16;
    public static final int MAX_HEAVY_TASKS_PER_TENANT = 4;
    public static final long MONTHLY_CREDIT_LIMIT_PER_TENANT = 10_000L;

    @Min(1)
    @Max(100)
    private int maxNonTerminalTasksPerUser = MAX_NON_TERMINAL_TASKS_PER_USER;

    @Min(1)
    @Max(1000)
    private int maxNonTerminalTasksPerTenant = MAX_NON_TERMINAL_TASKS_PER_TENANT;

    @Min(1)
    @Max(1000)
    private int maxHeavyTasksPerTenant = MAX_HEAVY_TASKS_PER_TENANT;

    @Min(1)
    private long monthlyCreditLimitPerTenant = MONTHLY_CREDIT_LIMIT_PER_TENANT;
}
