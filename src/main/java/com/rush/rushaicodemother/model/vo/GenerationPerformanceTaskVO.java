package com.rush.rushaicodemother.model.vo;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
public class GenerationPerformanceTaskVO {

    private String taskId;

    private Long appId;

    private Long userId;

    private String route;

    private String targetType;

    private String status;

    private Long totalDurationMs;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    private List<GenerationPerformanceSpanVO> spans;
}
