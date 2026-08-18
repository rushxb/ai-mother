package com.rush.rushaicodemother.ai.model;

/**
 * 生成性能配置。
 * <p>
 * 根据生成上下文（首次/改修、复杂度、类型）动态选择模型参数，
 * 以在质量和速度之间取得最优平衡。
 */
public record GenerationPerformanceProfile(
        ModelTier modelTier,
        boolean thinkingEnabled,
        int maxToolInvocations,
        String reasoning
) {

    /**
 * 返回{@code thinking}模式。
 *
 * @return 生成性能配置档
 */
    public GenerationThinkingMode thinkingMode() {
        if (modelTier == ModelTier.QUALITY && thinkingEnabled) {
            return GenerationThinkingMode.DEEP;
        }
        return modelTier == ModelTier.SPEED
                ? GenerationThinkingMode.FAST
                : GenerationThinkingMode.STANDARD;
    }

    /**
     * 模型层级。
     * <p>
     * SPEED  → deepseek-v4-flash，不开启 thinking，适合首次简单生成
     * BALANCED → deepseek-v4-flash，不开启 thinking，适合改修
     * QUALITY → deepseek-v4-pro，开启 thinking，适合复杂任务
     */
    public enum ModelTier {
        SPEED,
        BALANCED,
        QUALITY
    }

    /**
     * 为首次简单生成创建极速配置。
     */
    public static GenerationPerformanceProfile speedFirst() {
        return new GenerationPerformanceProfile(
                ModelTier.SPEED,
                false,
                15,
                "首次生成简单项目，使用轻量模型加速"
        );
    }

    /**
     * 为改修场景创建平衡配置。
     */
    public static GenerationPerformanceProfile balanced() {
        return new GenerationPerformanceProfile(
                ModelTier.BALANCED,
                false,
                10,
                "改修场景，使用平衡配置"
        );
    }

    /**
     * 为复杂任务创建质量优先配置。
     */
    public static GenerationPerformanceProfile qualityFirst() {
        return new GenerationPerformanceProfile(
                ModelTier.QUALITY,
                true,
                10,
                "复杂任务，使用推理模型确保质量"
        );
    }

}
