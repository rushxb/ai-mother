package com.rush.rushaicodemother.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/** 用户级吵闹邻居守卫，直到引入组织租户身份。 */
@Data
@Component
@Validated
public class GenerationTaskAdmissionProperties {

    public static final int MAX_NON_TERMINAL_TASKS_PER_USER = 4;

    @Min(1)
    @Max(100)
    private int maxNonTerminalTasksPerUser = MAX_NON_TERMINAL_TASKS_PER_USER;
}
