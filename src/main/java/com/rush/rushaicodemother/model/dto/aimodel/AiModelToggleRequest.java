package com.rush.rushaicodemother.model.dto.aimodel;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

/**
 * 切换 AI 模型启用状态请求。
 */
@Data
public class AiModelToggleRequest {

    @NotNull
    @Positive
    private Long id;
}