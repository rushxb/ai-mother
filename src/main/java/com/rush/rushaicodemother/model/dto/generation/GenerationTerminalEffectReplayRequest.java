package com.rush.rushaicodemother.model.dto.generation;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/** 管理员精确重放终态副作用 dead-letter 的请求。 */
@Data
public class GenerationTerminalEffectReplayRequest {

    @NotBlank
    @Pattern(regexp = "[A-Za-z0-9_-]{1,128}")
    private String taskId;

    @Min(1)
    private long executionEpoch;
}
