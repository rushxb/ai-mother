package com.rush.rushaicodemother.core.error;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerationErrorClassifierTest {

    @Test
    void shouldClassifyInsufficientBalanceAsModelQuota() {
        GenerationErrorClassifier.GenerationError error = GenerationErrorClassifier.classify(
                "{\"error\":{\"message\":\"Insufficient Balance\",\"type\":\"unknown_error\"}}"
        );

        assertEquals(GenerationErrorClassifier.CATEGORY_MODEL_QUOTA, error.category());
        assertFalse(error.recoverable());
    }

    @Test
    void shouldKeepBuildErrorsRecoverable() {
        GenerationErrorClassifier.GenerationError error = GenerationErrorClassifier.classify(
                "pnpm run build 执行失败: failed to resolve import"
        );

        assertEquals(GenerationErrorClassifier.CATEGORY_DEPENDENCY, error.category());
        assertTrue(error.recoverable());
    }
}
