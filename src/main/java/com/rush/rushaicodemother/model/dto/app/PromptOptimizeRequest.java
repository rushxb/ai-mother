package com.rush.rushaicodemother.model.dto.app;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/** 提示词优化请求。 */
@Data
public class PromptOptimizeRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 原始提示词。 */
    @NotBlank
    @Size(max = 1000)
    private String prompt;
}