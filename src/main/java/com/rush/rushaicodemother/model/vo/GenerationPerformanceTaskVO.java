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

    private String mode;

    private String routerReason;

    private String fallbackPolicy;

    private String fallbackReason;

    private String validationLevel;

    private String baseTemplate;

    private List<String> modules;

    private Integer slotGroupCount;

    private Integer aiCallCount;

    private Integer patchCount;

    private Long validationDurationMs;

    private Boolean createFallback;

    private String modelName;

    private Long firstTokenLatencyMs;

    private Long totalAiDurationMs;

    private Integer toolCallCount;

    private Long toolDurationMs;

    private Integer repairRounds;

    private String targetType;

    private String status;

    private Long totalDurationMs;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    private List<GenerationPerformanceSpanVO> spans;
}
