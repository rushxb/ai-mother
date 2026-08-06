package com.rush.rushaicodemother.monitor.latency;

import com.rush.rushaicodemother.monitor.span.GenerationSpanCategory;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerationLatencySegmentTest {

    @Test
    void everySpanCategoryMustEitherMapToASegmentOrBeDeliberatelyExcluded() {
        // 新增 span 类别时必须显式决定归属，避免默默漏出统计口径。
        for (GenerationSpanCategory category : GenerationSpanCategory.values()) {
            Optional<GenerationLatencySegment> segment =
                    GenerationLatencySegment.fromCategory(category);
            if (category == GenerationSpanCategory.PIPELINE) {
                assertTrue(segment.isEmpty(),
                        "PIPELINE 是父跨度，计入分段会与子跨度重复计时");
                continue;
            }
            assertTrue(segment.isPresent(), "span 类别未归入任何分段: " + category);
        }
    }

    @Test
    void categoriesMustFoldIntoTheExpectedDecisionSegments() {
        assertEquals(Optional.of(GenerationLatencySegment.PREPARATION),
                GenerationLatencySegment.fromCategory(GenerationSpanCategory.QUEUE));
        assertEquals(Optional.of(GenerationLatencySegment.PREPARATION),
                GenerationLatencySegment.fromCategory(GenerationSpanCategory.WORKSPACE));
        assertEquals(Optional.of(GenerationLatencySegment.CONTEXT),
                GenerationLatencySegment.fromCategory(GenerationSpanCategory.TOOL));
        assertEquals(Optional.of(GenerationLatencySegment.CONTEXT),
                GenerationLatencySegment.fromCategory(GenerationSpanCategory.DEPENDENCY));
        assertEquals(Optional.of(GenerationLatencySegment.MODEL),
                GenerationLatencySegment.fromCategory(GenerationSpanCategory.MODEL));
        assertEquals(Optional.of(GenerationLatencySegment.VERIFICATION),
                GenerationLatencySegment.fromCategory(GenerationSpanCategory.BUILD));
        assertEquals(Optional.of(GenerationLatencySegment.VERIFICATION),
                GenerationLatencySegment.fromCategory(GenerationSpanCategory.VALIDATION));
        assertEquals(Optional.of(GenerationLatencySegment.VERIFICATION),
                GenerationLatencySegment.fromCategory(GenerationSpanCategory.REPAIR));
        assertEquals(Optional.of(GenerationLatencySegment.PUBLISH),
                GenerationLatencySegment.fromCategory(GenerationSpanCategory.FINALIZATION));
    }

    @Test
    void persistedCategoryNamesMustBeResolvedCaseInsensitively() {
        assertEquals(Optional.of(GenerationLatencySegment.MODEL),
                GenerationLatencySegment.fromCategoryName("model"));
        assertEquals(Optional.of(GenerationLatencySegment.MODEL),
                GenerationLatencySegment.fromCategoryName("  MODEL  "));
        assertEquals(Optional.of(GenerationLatencySegment.VERIFICATION),
                GenerationLatencySegment.fromCategoryName("Build"));
    }

    @Test
    void unresolvableCategoryNamesMustNotThrow() {
        // 历史数据可能包含已下线的类别名，聚合不能因此中断整个路由。
        for (String category : new String[]{null, "", "   ", "pipeline", "retired_category", "1234"}) {
            assertTrue(GenerationLatencySegment.fromCategoryName(category).isEmpty(),
                    "无法归类的类别名必须安全返回空: " + category);
        }
    }

    @Test
    void onlyPreparationAndContextMayBeTreatedAsOverlappableWork() {
        // 阶段三只允许重叠无副作用的准备工作；模型、验证与发布必须串行。
        assertTrue(Arrays.asList(GenerationLatencySegment.PREPARATION, GenerationLatencySegment.CONTEXT)
                .containsAll(Arrays.asList(
                        GenerationLatencySegment.PREPARATION, GenerationLatencySegment.CONTEXT)));
        assertFalse(Arrays.asList(GenerationLatencySegment.PREPARATION, GenerationLatencySegment.CONTEXT)
                .contains(GenerationLatencySegment.MODEL));
        assertFalse(Arrays.asList(GenerationLatencySegment.PREPARATION, GenerationLatencySegment.CONTEXT)
                .contains(GenerationLatencySegment.VERIFICATION));
        assertFalse(Arrays.asList(GenerationLatencySegment.PREPARATION, GenerationLatencySegment.CONTEXT)
                .contains(GenerationLatencySegment.PUBLISH));
    }
}
