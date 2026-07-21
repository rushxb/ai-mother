package com.rush.rushaicodemother.orchestration.context;

import dev.langchain4j.model.TokenCountEstimator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OpenAiCompatibleContextTokenEstimatorTest {

    @Test
    void estimateAppliesConfiguredConservativeMargin() {
        TokenCountEstimator delegate = mock(TokenCountEstimator.class);
        when(delegate.estimateTokenCountInText("hello")).thenReturn(10);

        OpenAiCompatibleContextTokenEstimator estimator =
                new OpenAiCompatibleContextTokenEstimator(delegate, 1.15);

        assertEquals(12, estimator.estimate("hello"));
        assertEquals(0, estimator.estimate(""));
    }

    @Test
    void truncationUsesUnicodeCodePointsAndHonorsBothDirections() {
        TokenCountEstimator delegate = mock(TokenCountEstimator.class);
        when(delegate.estimateTokenCountInText(anyString())).thenAnswer(invocation -> {
            String text = invocation.getArgument(0);
            return text.codePointCount(0, text.length());
        });
        OpenAiCompatibleContextTokenEstimator estimator =
                new OpenAiCompatibleContextTokenEstimator(delegate, 1.0);

        String source = "A😀中文B";
        String head = estimator.truncate(source, 3);
        String tail = estimator.truncateFromEnd(source, 3);

        assertEquals("A😀中", head);
        assertEquals("中文B", tail);
        assertFalse(hasUnpairedSurrogate(head));
        assertFalse(hasUnpairedSurrogate(tail));
    }

    @Test
    void invalidSafetyMarginFailsClosed() {
        TokenCountEstimator delegate = mock(TokenCountEstimator.class);

        assertThrows(IllegalArgumentException.class,
                () -> new OpenAiCompatibleContextTokenEstimator(delegate, 0.99));
        assertThrows(IllegalArgumentException.class,
                () -> new OpenAiCompatibleContextTokenEstimator(delegate, 2.01));
    }

    private boolean hasUnpairedSurrogate(String value) {
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isHighSurrogate(current)) {
                if (index + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(index + 1))) {
                    return true;
                }
                index++;
            } else if (Character.isLowSurrogate(current)) {
                return true;
            }
        }
        return false;
    }
}
