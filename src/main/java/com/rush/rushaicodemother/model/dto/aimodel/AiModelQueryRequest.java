package com.rush.rushaicodemother.model.dto.aimodel;

import com.rush.rushaicodemother.common.PageRequest;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * AI 模型分页查询请求。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class AiModelQueryRequest extends PageRequest {

    @Size(max = 50)
    private String provider;

    @Size(max = 30)
    private String modelType;

    @Min(0)
    @Max(1)
    private Integer isEnabled;

    @Size(max = 100)
    private String keyword;
}