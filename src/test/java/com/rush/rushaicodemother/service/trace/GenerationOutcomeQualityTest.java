package com.rush.rushaicodemother.service.trace;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerationOutcomeQualityTest {

    @Test
    void emptyEvidenceMustLeaveEveryFieldUncollected() {
        GenerationOutcomeQuality quality = GenerationOutcomeQuality.empty();

        assertTrue(quality.isEmpty());
        assertNull(quality.thinkingMode());
        assertNull(quality.changedFileCount());
        assertNull(quality.firstBuildPassed());
        assertNull(quality.repairRounds());
        assertNull(quality.firstPreviewMillis());
        assertNull(quality.failureCategory());
        assertNull(quality.reworkedAt());
        assertNull(quality.distilledAt());
        assertNull(quality.firstBuildPassedValue());
    }

    @Test
    void successEvidenceMustCarryAttributionMetricsWithoutFailureCategory() {
        GenerationOutcomeQuality quality = GenerationOutcomeQuality.ofSuccess(4, 0, true, 12_000L);

        assertFalse(quality.isEmpty());
        assertEquals(4, quality.changedFileCount());
        assertEquals(0, quality.repairRounds());
        assertEquals(Boolean.TRUE, quality.firstBuildPassed());
        assertEquals(1, quality.firstBuildPassedValue());
        assertEquals(12_000L, quality.firstPreviewMillis());
        assertNull(quality.failureCategory());
    }

    @Test
    void failureEvidenceMustNormalizeCategoryAndOmitBuildOutcome() {
        GenerationOutcomeQuality quality = GenerationOutcomeQuality.ofFailure(
                "  MODEL_TIMEOUT  ", 0, 2, null);

        assertEquals("model_timeout", quality.failureCategory());
        assertEquals(0, quality.changedFileCount());
        assertEquals(2, quality.repairRounds());
        assertNull(quality.firstBuildPassed());
        assertNull(quality.firstPreviewMillis());
    }

    @Test
    void falseBuildOutcomeMustPersistAsZeroRatherThanUncollected() {
        // 0 与 null 在数据库里语义不同：0 表示「确实没免修复通过」，null 表示「未采集」。
        GenerationOutcomeQuality quality = GenerationOutcomeQuality.ofSuccess(1, 2, false, null);

        assertEquals(Boolean.FALSE, quality.firstBuildPassed());
        assertEquals(0, quality.firstBuildPassedValue());
    }

    @Test
    void blankTokensMustCollapseToUncollected() {
        GenerationOutcomeQuality quality = new GenerationOutcomeQuality(
                "   ", null, null, null, null, "", null, null);

        assertNull(quality.thinkingMode());
        assertNull(quality.failureCategory());
        assertTrue(quality.isEmpty());
    }

    @Test
    void negativeMetricsMustBeRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> GenerationOutcomeQuality.ofSuccess(-1, 0, true, 0L));
        assertThrows(IllegalArgumentException.class,
                () -> GenerationOutcomeQuality.ofSuccess(0, -1, true, 0L));
        assertThrows(IllegalArgumentException.class,
                () -> GenerationOutcomeQuality.ofSuccess(0, 0, true, -1L));
    }

    @Test
    void oversizedTokensMustBeRejectedRatherThanSilentlyTruncated() {
        // 静默截断会让归因数据出现半截分类名，宁可拒绝。
        assertThrows(IllegalArgumentException.class, () -> new GenerationOutcomeQuality(
                "a".repeat(17), null, null, null, null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> new GenerationOutcomeQuality(
                null, null, null, null, null, "b".repeat(65), null, null));
    }

    @Test
    void deferredFieldsMustRemainRepresentableForFutureCapabilities() {
        LocalDateTime reworkedAt = LocalDateTime.parse("2026-08-04T10:00:00");
        LocalDateTime distilledAt = LocalDateTime.parse("2026-08-05T10:00:00");
        GenerationOutcomeQuality quality = new GenerationOutcomeQuality(
                "deep", 3, true, 1, 5_000L, null, reworkedAt, distilledAt);

        assertEquals("deep", quality.thinkingMode());
        assertEquals(reworkedAt, quality.reworkedAt());
        assertEquals(distilledAt, quality.distilledAt());
    }
}
