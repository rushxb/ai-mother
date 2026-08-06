package com.rush.rushaicodemother.orchestration.context;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/** 上下文包的有界令牌估计固定策略。 */
@Data
@Component
@Validated
public class AiContextPackBudgetProperties {

    public static final int GENERATION_MAX_TOKENS = 2_000;
    public static final int REPAIR_MAX_TOKENS = 1_500;
    public static final String TOKENIZER_MODEL = "gpt-4o";
    public static final double TOKEN_SAFETY_MARGIN = 1.15;
    public static final int MAX_SECTION_TOKENS = 800;
    public static final int MINIMUM_SECTION_TOKENS = 64;
    public static final int MAX_SEMANTIC_MEMORY_SECTIONS = 6;
    public static final Duration SEMANTIC_MEMORY_HALF_LIFE = Duration.ofDays(30);
    public static final double MINIMUM_SEMANTIC_TRUST = 0.25;

    @Min(256)
    @Max(32_000)
    private int generationMaxTokens = GENERATION_MAX_TOKENS;

    @Min(256)
    @Max(32_000)
    private int repairMaxTokens = REPAIR_MAX_TOKENS;

    /** 用于 OpenAI 兼容模型输入预算的稳定分词器。 */
    @NotBlank
    private String tokenizerModel = TOKENIZER_MODEL;

    /** 涵盖 OpenAI 兼容提供商和提示框架之间的分词器差异。 */
    @DecimalMin("1.0")
    @DecimalMax("2.0")
    private double tokenSafetyMargin = TOKEN_SAFETY_MARGIN;

    @Min(64)
    @Max(8_000)
    private int maxSectionTokens = MAX_SECTION_TOKENS;

    @Min(16)
    @Max(1_000)
    private int minimumSectionTokens = MINIMUM_SECTION_TOKENS;

    @Min(1)
    @Max(20)
    private int maxSemanticMemorySections = MAX_SEMANTIC_MEMORY_SECTIONS;

    private Duration semanticMemoryHalfLife = SEMANTIC_MEMORY_HALF_LIFE;

    private double minimumSemanticTrust = MINIMUM_SEMANTIC_TRUST;

    /**
 * 校验当前配置项组合是否合法。
 *
 * @return 满足条件时返回 {@code true}，否则返回 {@code false}
 */
    @AssertTrue(message = "AI context pack budget configuration is invalid")
    public boolean isConfigurationValid() {
        return generationMaxTokens >= minimumSectionTokens
                && repairMaxTokens >= minimumSectionTokens
                && maxSectionTokens >= minimumSectionTokens
                && semanticMemoryHalfLife != null
                && !semanticMemoryHalfLife.isZero()
                && !semanticMemoryHalfLife.isNegative()
                && tokenizerModel != null
                && !tokenizerModel.isBlank()
                && tokenSafetyMargin >= 1.0
                && tokenSafetyMargin <= 2.0
                && minimumSemanticTrust >= 0.0
                && minimumSemanticTrust <= 1.0;
    }

    public int maxTokens(String targetType) {
        return "repair".equalsIgnoreCase(targetType) ? repairMaxTokens : generationMaxTokens;
    }
}
