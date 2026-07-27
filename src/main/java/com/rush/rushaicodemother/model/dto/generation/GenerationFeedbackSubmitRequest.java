package com.rush.rushaicodemother.model.dto.generation;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 生成反馈提交请求参数。
 */
@Data
public class GenerationFeedbackSubmitRequest {

    /** 评分。 */
    @Min(1)
    @Max(5)
    @NotNull
    private Integer rating;

    /** 执行结果。 */
    @Size(max = 64)
    private String outcome;

    /** 评论内容。 */
    @Size(max = 2000)
    private String comment;
}
