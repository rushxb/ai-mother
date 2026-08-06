package com.rush.rushaicodemother.monitor.latency;

import com.rush.rushaicodemother.monitor.span.GenerationSpanCategory;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 面向并行决策的延迟分段。
 *
 * <p>{@link GenerationSpanCategory} 有 10 个取值，直接按它做路由级聚合过于零碎，无法回答
 * 「准备阶段占可预览时间多少」这一类问题。此处把类别折叠为 5 个决策分段：只有
 * {@link #PREPARATION} 与 {@link #CONTEXT} 属于可与模型推理重叠的准备工作，
 * {@link #MODEL} 不可并行，{@link #VERIFICATION} 与 {@link #PUBLISH} 必须串行。</p>
 *
 * <p>{@link GenerationSpanCategory#PIPELINE} 刻意不映射到任何分段：它是包裹子跨度的父跨度，
 * 计入分段会与子跨度重复计时。它只作为任务总时长的参照。</p>
 */
public enum GenerationLatencySegment {

    /** 排队与工作区物化，阶段三的可重叠对象。 */
    PREPARATION,

    /** 语义索引、只读工具与依赖元数据读取。 */
    CONTEXT,

    /** 模型推理，不可并行。 */
    MODEL,

    /** 构建、校验与修复。 */
    VERIFICATION,

    /** 提交、发布与预览。 */
    PUBLISH;

    private static final Map<GenerationSpanCategory, GenerationLatencySegment> SEGMENTS_BY_CATEGORY =
            Map.of(
                    GenerationSpanCategory.QUEUE, PREPARATION,
                    GenerationSpanCategory.WORKSPACE, PREPARATION,
                    GenerationSpanCategory.TOOL, CONTEXT,
                    GenerationSpanCategory.DEPENDENCY, CONTEXT,
                    GenerationSpanCategory.MODEL, MODEL,
                    GenerationSpanCategory.BUILD, VERIFICATION,
                    GenerationSpanCategory.VALIDATION, VERIFICATION,
                    GenerationSpanCategory.REPAIR, VERIFICATION,
                    GenerationSpanCategory.FINALIZATION, PUBLISH
            );

    /**
     * 把 span 类别折叠为决策分段。
     *
     * @param category span 类别，允许为空
     * @return 对应分段；{@code PIPELINE}、未知值与空值返回 {@link Optional#empty()}
     */
    public static Optional<GenerationLatencySegment> fromCategory(GenerationSpanCategory category) {
        return category == null
                ? Optional.empty()
                : Optional.ofNullable(SEGMENTS_BY_CATEGORY.get(category));
    }

    /**
     * 按持久化的类别名称折叠为决策分段。
     *
     * <p>span 以字符串形式落库，历史数据可能包含已下线的类别名，因此无法解析时按
     * 「未归入分段」处理，而不是抛出异常中断整个路由的聚合。</p>
     *
     * @param category 持久化的类别名称，允许为空或未知
     * @return 对应分段；无法归类时返回 {@link Optional#empty()}
     */
    public static Optional<GenerationLatencySegment> fromCategoryName(String category) {
        if (category == null || category.isBlank()) {
            return Optional.empty();
        }
        try {
            return fromCategory(GenerationSpanCategory.valueOf(
                    category.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException unknownCategory) {
            return Optional.empty();
        }
    }
}
