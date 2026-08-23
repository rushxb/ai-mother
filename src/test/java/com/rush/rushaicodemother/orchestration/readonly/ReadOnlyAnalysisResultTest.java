package com.rush.rushaicodemother.orchestration.readonly;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;

class ReadOnlyAnalysisResultTest {

    @Test
    void blankFindingMustNotBecomeIntentCoverageThroughPlaceholderText() {
        ReadOnlyAnalysisResult result = new ReadOnlyAnalysisResult(
                null,
                List.of(new ReadOnlyAnalysisResult.Finding(" ", "HIGH", " ")),
                List.of(),
                "本次请求仅要求审计，因此未修改工作区"
        );

        assertThrows(IllegalStateException.class, result::requireIntentCoverage);
    }
}
