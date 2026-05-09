package com.yupi.yuaicodemother.model.dto.app;

import lombok.Data;

import java.io.Serializable;

/**
 * 提示词优化请求
 */
@Data
public class PromptOptimizeRequest implements Serializable {

    /**
     * 原始提示词
     */
    private String prompt;

    private static final long serialVersionUID = 1L;
}
