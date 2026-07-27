package com.rush.rushaicodemother.orchestration.context;

import dev.langchain4j.model.TokenCountEstimator;
import dev.langchain4j.model.openai.OpenAiTokenCountEstimator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Objects;

/** 使用与 OpenAI 兼容的生产分词器，并具有可配置的保守裕度。 */
@Component
public class OpenAiCompatibleContextTokenEstimator implements AiContextTokenEstimator {

    private final TokenCountEstimator delegate;
    private final double safetyMargin;

    @Autowired
    public OpenAiCompatibleContextTokenEstimator(AiContextPackBudgetProperties properties) {
        this(createEstimator(properties), properties.getTokenSafetyMargin());
    }

    OpenAiCompatibleContextTokenEstimator(TokenCountEstimator delegate, double safetyMargin) {
        if (safetyMargin < 1.0 || safetyMargin > 2.0) {
            throw new IllegalArgumentException("context token safety margin is invalid");
        }
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.safetyMargin = safetyMargin;
    }

    @Override
    public int estimate(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int rawCount = delegate.estimateTokenCountInText(text);
        if (rawCount <= 0) {
            return 0;
        }
        return Math.max(1, (int) Math.ceil(rawCount * safetyMargin));
    }

    @Override
    public String truncate(String text, int maximumTokens) {
        return truncate(text, maximumTokens, false);
    }

    @Override
    public String truncateFromEnd(String text, int maximumTokens) {
        return truncate(text, maximumTokens, true);
    }

    private String truncate(String text, int maximumTokens, boolean fromEnd) {
        if (text == null || text.isEmpty() || maximumTokens <= 0) {
            return "";
        }
        if (estimate(text) <= maximumTokens) {
            return text;
        }
        int[] codePoints = text.codePoints().toArray();
        int low = 0;
        int high = codePoints.length;
        String best = "";
        while (low <= high) {
            int middle = (low + high) >>> 1;
            int offset = fromEnd ? codePoints.length - middle : 0;
            String candidate = new String(codePoints, offset, middle);
            if (estimate(candidate) <= maximumTokens) {
                best = candidate;
                low = middle + 1;
            } else {
                high = middle - 1;
            }
        }
        return best;
    }

    private static TokenCountEstimator createEstimator(AiContextPackBudgetProperties properties) {
        Objects.requireNonNull(properties, "properties");
        try {
            return new OpenAiTokenCountEstimator(properties.getTokenizerModel());
        } catch (RuntimeException invalidModel) {
            throw new IllegalArgumentException(
                    "AI context tokenizer model is unsupported: " + properties.getTokenizerModel(),
                    invalidModel
            );
        }
    }
}
