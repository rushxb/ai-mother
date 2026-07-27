package com.rush.rushaicodemother.model.vo;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 生成性能任务接口视图对象。
 */
@Data
@Builder
public class GenerationPerformanceTaskVO {

    /** 生成任务编号。 */
    private String taskId;

    /** 应用编号。 */
    private Long appId;

    /** 用户编号。 */
    private Long userId;

    /** 生成路由。 */
    private String route;

    /** 运行模式。 */
    private String mode;

    private String routerReason;

    private String routingDecisionCode;

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

    /** 模型名称。 */
    private String modelName;

    private Long firstTokenLatencyMs;

    private Long totalAiDurationMs;

    private Integer toolCallCount;

    private Long toolDurationMs;

    private Integer repairRounds;

    private String targetType;

    /** 当前状态。 */
    private String status;

    private Long totalDurationMs;

    /** 开始时间。 */
    private LocalDateTime startTime;

    /** 结束时间。 */
    private LocalDateTime endTime;

    private List<GenerationPerformanceSpanVO> spans;
}
