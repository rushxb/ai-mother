package com.rush.rushaicodemother.orchestration.context;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/** 上下文包的有界令牌估计策略。 */
@Data
@Component
@Validated
@ConfigurationProperties(prefix = "app.ai-context-pack")
public class AiContextPackBudgetProperties {

    @Min(256)
    @Max(32_000)
    private int generationMaxTokens = 2_000;

    @Min(256)
    @Max(32_000)
    private int repairMaxTokens = 1_500;

    /** 用于 OpenAI 兼容模型输入预算的稳定分词器。 */
    @NotBlank
    private String tokenizerModel = "gpt-4o";

    /** 涵盖 OpenAI 兼容提供商和提示框架之间的分词器差异。 */
    @DecimalMin("1.0")
    @DecimalMax("2.0")
    private double tokenSafetyMargin = 1.15;

    @Min(64)
    @Max(8_000)
    private int maxSectionTokens = 800;

    @Min(16)
    @Max(1_000)
    private int minimumSectionTokens = 64;

    @Min(1)
    @Max(20)
    private int maxSemanticMemorySections = 6;

    private Duration semanticMemoryHalfLife = Duration.ofDays(30);

    private double minimumSemanticTrust = 0.25;

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
