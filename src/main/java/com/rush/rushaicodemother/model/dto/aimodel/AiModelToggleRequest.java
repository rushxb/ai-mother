package com.rush.rushaicodemother.model.dto.aimodel;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * 切换 AI 模型启用状态请求。
 */
@Data
public class AiModelToggleRequest {

    @NotNull
    @Positive
    private Long id;

    @Pattern(regexp = "[0-9a-fA-F-]{36}")
    private String evidenceId;
}
