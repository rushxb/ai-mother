package com.rush.rushaicodemother.model.dto.generation;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class GenerationFeedbackSubmitRequest {

    @Min(1)
    @Max(5)
    @NotNull
    private Integer rating;

    @Size(max = 64)
    private String outcome;

    @Size(max = 2000)
    private String comment;
}
