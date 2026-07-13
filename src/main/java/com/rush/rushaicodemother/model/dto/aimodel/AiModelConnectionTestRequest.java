package com.rush.rushaicodemother.model.dto.aimodel;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

/**
 * 已保存 AI 模型连接测试请求。
 */
@Data
public class AiModelConnectionTestRequest {

    @NotNull
    @Positive
    private Long id;
}