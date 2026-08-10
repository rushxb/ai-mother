package com.rush.rushaicodemother.orchestration.runtime.execution;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * 以原子方式发布第一个可用预览里程碑的结果。
 *
 * @param firstPublication 是否为该等级在本执行纪元内的首次发布
 * @param readyAt 就绪时刻
 * @param deadlineAt 首预览截止线
 * @param elapsed 自任务开始到就绪的耗时
 * @param slaBreached 是否超过首预览截止线
 * @param level 预览成熟度；{@code PROVISIONAL} 不构成交付语义
 */
public record GenerationFirstPreviewMilestone(
        boolean firstPublication,
        Instant readyAt,
        Instant deadlineAt,
        Duration elapsed,
        boolean slaBreached,
        GenerationPreviewLevel level
) {

    public GenerationFirstPreviewMilestone {
        Objects.requireNonNull(level, "预览等级不能为空");
    }

    /** 兼容既有调用：未显式声明等级时按已验证发布处理。 */
    public GenerationFirstPreviewMilestone(
            boolean firstPublication,
            Instant readyAt,
            Instant deadlineAt,
            Duration elapsed,
            boolean slaBreached
    ) {
        this(firstPublication, readyAt, deadlineAt, elapsed, slaBreached,
                GenerationPreviewLevel.VERIFIED);
    }

    /** 是否为暂定预览：调用方据此决定跳过计费、终态与完成证据。 */
    public boolean provisional() {
        return level == GenerationPreviewLevel.PROVISIONAL;
    }
}
