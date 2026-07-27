package com.rush.rushaicodemother.model.vo;

import lombok.Builder;
import lombok.Data;

/**
 * 生成性能跨度接口视图对象。
 */
@Data
@Builder
public class GenerationPerformanceSpanVO {

    /** 当前阶段。 */
    private String stage;

    /** 当前状态。 */
    private String status;

    /** 耗时毫秒数。 */
    private Long durationMs;

    /** 详细信息。 */
    private String detail;
}
