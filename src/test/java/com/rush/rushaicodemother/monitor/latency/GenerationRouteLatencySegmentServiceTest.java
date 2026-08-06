package com.rush.rushaicodemother.monitor.latency;

import com.rush.rushaicodemother.orchestration.runtime.task.progress.GenerationDurationSampleRepository;
import com.rush.rushaicodemother.orchestration.runtime.task.progress.GenerationDurationSampleRepository.GenerationDurationSamples;
import com.rush.rushaicodemother.orchestration.runtime.task.progress.GenerationDurationSampleRepository.GenerationStageDurationSample;
import com.rush.rushaicodemother.orchestration.runtime.task.progress.GenerationTaskProgressProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerationRouteLatencySegmentServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-04T00:00:00Z");

    private RecordingSampleRepository repository;
    private GenerationRouteLatencySegmentService service;

    @BeforeEach
    void setUp() {
        repository = new RecordingSampleRepository();
        service = new GenerationRouteLatencySegmentService(
                repository,
                new GenerationTaskProgressProperties(),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void segmentPercentilesMustFoldCategoriesAndReportShareOfTaskTotal() {
        repository.samples = new GenerationDurationSamples(
                List.of(1_000L),
                List.of(
                        // PREPARATION = QUEUE + WORKSPACE
                        new GenerationStageDurationSample("stage_QUEUE", "QUEUE", 100L),
                        new GenerationStageDurationSample("stage_WORKSPACE", "WORKSPACE", 300L),
                        // MODEL
                        new GenerationStageDurationSample("stage_MODEL", "MODEL", 500L)
                ));

        GenerationRouteLatencySegmentProfile profile = service.getProfile("create");

        assertEquals("create", profile.route());
        assertEquals(1_000L, profile.taskTotalP90Ms());
        // 分段按 p90 降序：MODEL(500) 在 PREPARATION(300) 之前
        assertEquals(GenerationLatencySegment.MODEL, profile.segments().getFirst().segment());

        GenerationRouteLatencySegmentProfile.SegmentLatency preparation =
                segment(profile, GenerationLatencySegment.PREPARATION);
        assertEquals(2, preparation.spanCount());
        assertEquals(300L, preparation.p90DurationMs());
        // 300 / 1000 = 30%
        assertEquals(30.0d, preparation.taskP90SharePercent());
    }

    @Test
    void pipelineParentSpansMustNotBeCountedIntoAnySegment() {
        repository.samples = new GenerationDurationSamples(
                List.of(1_000L),
                List.of(
                        new GenerationStageDurationSample("stage_PIPELINE", "PIPELINE", 900L),
                        new GenerationStageDurationSample("stage_MODEL", "MODEL", 500L)
                ));

        GenerationRouteLatencySegmentProfile profile = service.getProfile("create");

        assertEquals(1, profile.segments().size());
        assertEquals(GenerationLatencySegment.MODEL, profile.segments().getFirst().segment());
        assertEquals(2, profile.spanSampleCount());
        assertEquals(1, profile.unmappedSpanCount());
        // 父跨度计入完整率分母，使归类缺口可见：1/2 = 50%
        assertEquals(50.0d, profile.sampleCompletenessPercent());
    }

    @Test
    void emptySamplesMustYieldZeroedProfileInsteadOfThrowing() {
        repository.samples = new GenerationDurationSamples(List.of(), List.of());

        GenerationRouteLatencySegmentProfile profile = service.getProfile("agent_edit");

        assertEquals(0, profile.taskSampleCount());
        assertEquals(0, profile.spanSampleCount());
        assertEquals(0L, profile.taskTotalP90Ms());
        assertEquals(0.0d, profile.sampleCompletenessPercent());
        assertTrue(profile.segments().isEmpty());
        assertFalse(profile.sufficientForParallelDecision(),
                "空样本绝不能被当作可用于并行决策");
        assertEquals(NOW, profile.calculatedAt());
    }

    @Test
    void parallelDecisionMustRequireBothSampleCountAndCompleteness() {
        // 99 个样本 + 完整归类：样本量不足
        repository.samples = samplesWith(99, 0);
        assertFalse(service.getProfile("create").sufficientForParallelDecision(),
                "99 个样本低于门禁，不得据此决策");

        // 100 个样本 + 完整归类：达标
        service.invalidate("create");
        repository.samples = samplesWith(100, 0);
        assertTrue(service.getProfile("create").sufficientForParallelDecision(),
                "100 个样本且归类完整应达到门禁");

        // 100 个样本 + 归类完整率 90%（10 个父跨度）：完整率不足
        service.invalidate("create");
        repository.samples = samplesWith(100, 10);
        GenerationRouteLatencySegmentProfile incomplete = service.getProfile("create");
        assertEquals(90.0d, incomplete.sampleCompletenessPercent());
        assertFalse(incomplete.sufficientForParallelDecision(),
                "归类完整率低于 95% 时不得据此决策");
    }

    @Test
    void oversizedAndNonPositiveDurationsMustBeDiscarded() {
        long beyondMaximum = new GenerationTaskProgressProperties()
                .getMaximumEstimatedDuration().toMillis() + 1;
        repository.samples = new GenerationDurationSamples(
                List.of(1_000L, -5L, 0L, beyondMaximum),
                List.of(
                        new GenerationStageDurationSample("stage_MODEL", "MODEL", 500L),
                        new GenerationStageDurationSample("stage_MODEL", "MODEL", 0L),
                        new GenerationStageDurationSample("stage_MODEL", "MODEL", -1L),
                        new GenerationStageDurationSample("stage_MODEL", "MODEL", beyondMaximum)
                ));

        GenerationRouteLatencySegmentProfile profile = service.getProfile("create");

        assertEquals(1, profile.taskSampleCount());
        assertEquals(1, profile.spanSampleCount());
        assertEquals(1, segment(profile, GenerationLatencySegment.MODEL).spanCount());
    }

    @Test
    void repeatedReadsMustBeServedFromCacheUntilInvalidated() {
        repository.samples = samplesWith(10, 0);

        service.getProfile("create");
        service.getProfile("create");
        assertEquals(1, repository.callCount, "缓存命中不应重复查询样本");

        service.invalidate("create");
        service.getProfile("create");
        assertEquals(2, repository.callCount, "失效后必须重新加载");
    }

    @Test
    void routeIdentityMustBeNormalizedAndValidated() {
        repository.samples = samplesWith(10, 0);

        service.getProfile("CREATE");
        assertEquals("create", repository.lastRoute, "路由必须归一化为小写");

        for (String invalid : new String[]{null, "", "   ", "bad route", "../etc", "a".repeat(65)}) {
            assertThrows(IllegalArgumentException.class, () -> service.getProfile(invalid),
                    "非法路由必须拒绝: " + invalid);
        }
    }

    private GenerationRouteLatencySegmentProfile.SegmentLatency segment(
            GenerationRouteLatencySegmentProfile profile,
            GenerationLatencySegment segment
    ) {
        Optional<GenerationRouteLatencySegmentProfile.SegmentLatency> found = profile.segments().stream()
                .filter(candidate -> candidate.segment() == segment)
                .findFirst();
        assertTrue(found.isPresent(), "缺少分段: " + segment);
        return found.get();
    }

    /** 构造指定数量的成功任务样本与 span 样本，其中部分为不归类的父跨度。 */
    private GenerationDurationSamples samplesWith(int taskCount, int pipelineSpanCount) {
        List<Long> taskDurations = IntStream.range(0, taskCount)
                .mapToObj(index -> 1_000L + index)
                .toList();
        List<GenerationStageDurationSample> spans = new ArrayList<>();
        int mappedSpanCount = Math.max(0, taskCount - pipelineSpanCount);
        IntStream.range(0, mappedSpanCount).forEach(index ->
                spans.add(new GenerationStageDurationSample("stage_MODEL", "MODEL", 500L)));
        IntStream.range(0, pipelineSpanCount).forEach(index ->
                spans.add(new GenerationStageDurationSample("stage_PIPELINE", "PIPELINE", 900L)));
        return new GenerationDurationSamples(taskDurations, spans);
    }

    /** 记录调用次数的测试替身，用于验证缓存与路由归一化。 */
    private static final class RecordingSampleRepository implements GenerationDurationSampleRepository {

        private GenerationDurationSamples samples = new GenerationDurationSamples(List.of(), List.of());
        private int callCount;
        private String lastRoute;

        @Override
        public GenerationDurationSamples loadRecentSuccessfulSamples(String route,
                                                                     int taskSampleLimit,
                                                                     int spanSampleLimit) {
            callCount++;
            lastRoute = route;
            return samples;
        }
    }
}
