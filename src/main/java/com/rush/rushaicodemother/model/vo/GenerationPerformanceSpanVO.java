package com.rush.rushaicodemother.model.vo;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class GenerationPerformanceSpanVO {

    private String stage;

    private String status;

    private Long durationMs;

    private String detail;
}
