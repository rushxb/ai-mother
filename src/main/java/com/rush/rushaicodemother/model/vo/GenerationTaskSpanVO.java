package com.rush.rushaicodemother.model.vo;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

/** 管理员诊断 API 返回的持久关键路径跨度。 */
@Data
@Builder
public class GenerationTaskSpanVO {

    private String spanId;
    private String taskId;
    private String stage;
    private String category;
    private String status;
    private Instant startedAt;
    private Instant endedAt;
    private Long durationMs;
    private String detail;
}
