package com.rush.rushaicodemother.model.vo;

import lombok.Builder;
import lombok.Data;

/**
 * AI 模型连接测试结果
 */
@Data
@Builder
public class AiModelConnectionTestResultVO {

    private Boolean success;

    private String message;
}
