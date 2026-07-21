package com.rush.rushaicodemother.ai.model.failover;

import com.rush.rushaicodemother.core.error.GenerationErrorClassifier;
import dev.langchain4j.exception.AuthenticationException;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.exception.InternalServerException;
import dev.langchain4j.exception.NonRetriableException;
import dev.langchain4j.exception.RateLimitException;
import dev.langchain4j.exception.RetriableException;
import dev.langchain4j.exception.TimeoutException;
import dev.langchain4j.exception.UnresolvedModelServerException;

/** Restricts cross-model failover to explicit transient upstream failures. */
final class AiModelFailoverPolicy {

    private static final int MAX_CAUSE_DEPTH = 16;

    private AiModelFailoverPolicy() {
    }

    static Decision classify(Throwable failure) {
        GenerationErrorClassifier.GenerationError generic =
                GenerationErrorClassifier.classify(failure);
        if (isConfigurationOrQuotaFailure(generic.category())) {
            return new Decision(false, generic.category());
        }

        Throwable current = failure;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
            Decision typed = classifyTyped(current, generic.category());
            if (typed != null) {
                return typed;
            }
            Throwable cause = current.getCause();
            current = cause == current ? null : cause;
        }

        return switch (generic.category()) {
            case GenerationErrorClassifier.CATEGORY_MODEL_RATE_LIMIT,
                 GenerationErrorClassifier.CATEGORY_MODEL_TIMEOUT,
                 GenerationErrorClassifier.CATEGORY_MODEL_UNAVAILABLE ->
                    new Decision(true, generic.category());
            default -> new Decision(false, generic.category());
        };
    }

    private static Decision classifyTyped(Throwable failure, String fallbackCategory) {
        if (failure instanceof AuthenticationException) {
            return new Decision(false, GenerationErrorClassifier.CATEGORY_MODEL_AUTH);
        }
        if (failure instanceof RateLimitException) {
            return new Decision(true, GenerationErrorClassifier.CATEGORY_MODEL_RATE_LIMIT);
        }
        if (failure instanceof TimeoutException) {
            return new Decision(true, GenerationErrorClassifier.CATEGORY_MODEL_TIMEOUT);
        }
        if (failure instanceof InternalServerException
                || failure instanceof UnresolvedModelServerException) {
            return new Decision(true, GenerationErrorClassifier.CATEGORY_MODEL_UNAVAILABLE);
        }
        if (failure instanceof HttpException httpFailure) {
            return classifyHttpStatus(httpFailure.statusCode(), fallbackCategory);
        }
        if (failure instanceof NonRetriableException) {
            return new Decision(false, fallbackCategory);
        }
        if (failure instanceof RetriableException) {
            return new Decision(true, GenerationErrorClassifier.CATEGORY_MODEL_UNAVAILABLE);
        }
        return null;
    }

    private static Decision classifyHttpStatus(int statusCode, String fallbackCategory) {
        if (statusCode == 401 || statusCode == 403) {
            return new Decision(false, GenerationErrorClassifier.CATEGORY_MODEL_AUTH);
        }
        if (statusCode == 408) {
            return new Decision(true, GenerationErrorClassifier.CATEGORY_MODEL_TIMEOUT);
        }
        if (statusCode == 429) {
            return new Decision(true, GenerationErrorClassifier.CATEGORY_MODEL_RATE_LIMIT);
        }
        if (statusCode == 409 || statusCode == 425 || statusCode >= 500) {
            return new Decision(true, GenerationErrorClassifier.CATEGORY_MODEL_UNAVAILABLE);
        }
        return new Decision(false, fallbackCategory);
    }

    private static boolean isConfigurationOrQuotaFailure(String category) {
        return GenerationErrorClassifier.CATEGORY_MODEL_AUTH.equals(category)
                || GenerationErrorClassifier.CATEGORY_MODEL_QUOTA.equals(category)
                || GenerationErrorClassifier.CATEGORY_PERMISSION.equals(category);
    }

    record Decision(boolean recoverable, String category) {
    }
}
