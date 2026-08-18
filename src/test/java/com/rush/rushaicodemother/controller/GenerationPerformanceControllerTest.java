package com.rush.rushaicodemother.controller;

import com.rush.rushaicodemother.annotation.AuthCheck;
import com.rush.rushaicodemother.common.BaseResponse;
import com.rush.rushaicodemother.constant.UserConstant;
import com.rush.rushaicodemother.config.GenerationBenchmarkReleaseProperties;
import com.rush.rushaicodemother.model.vo.GenerationDurationProfileVO;
import com.rush.rushaicodemother.model.vo.GenerationTaskSpanVO;
import com.rush.rushaicodemother.monitor.GenerationPerformanceMonitorService;
import com.rush.rushaicodemother.monitor.latency.GenerationTaskLatencyLedger;
import com.rush.rushaicodemother.monitor.latency.GenerationRouteLatencySegmentService;
import com.rush.rushaicodemother.monitor.latency.GenerationTaskLatencyLedgerService;
import com.rush.rushaicodemother.monitor.span.GenerationSpanQueryService;
import com.rush.rushaicodemother.orchestration.runtime.task.progress.GenerationDurationProfile;
import com.rush.rushaicodemother.orchestration.runtime.task.progress.GenerationDurationProfileService;
import com.rush.rushaicodemother.orchestration.runtime.task.progress.GenerationStageDurationProfile;
import com.rush.rushaicodemother.orchestration.learning.GenerationScenarioAttributionRepository;
import com.rush.rushaicodemother.orchestration.learning.GenerationStrategyPromotionGate;
import com.rush.rushaicodemother.orchestration.learning.GenerationStrategyPromotionService;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GenerationPerformanceControllerTest {

    @Test
    void getTaskSpansMustDelegateAndMapStoredSpansWithoutDroppingDiagnostics() {
        GenerationPerformanceMonitorService monitorService = mock(GenerationPerformanceMonitorService.class);
        GenerationSpanQueryService queryService = mock(GenerationSpanQueryService.class);
        GenerationPerformanceController controller = new GenerationPerformanceController(
                monitorService, queryService, mock(GenerationDurationProfileService.class),
                mock(GenerationTaskLatencyLedgerService.class),
                mock(GenerationRouteLatencySegmentService.class),
                promotionService());
        Instant startedAt = Instant.parse("2026-07-16T02:00:00Z");
        Instant endedAt = startedAt.plusSeconds(12);
        when(queryService.findByTaskId("task-span-1", 25)).thenReturn(List.of(
                new GenerationSpanQueryService.StoredSpan(
                        "span-1",
                        "task-span-1",
                        "pnpm_install",
                        "DEPENDENCY",
                        "success",
                        startedAt,
                        endedAt,
                        12_000L,
                        "exitCode=0"
                )
        ));

        BaseResponse<List<GenerationTaskSpanVO>> response = controller.getTaskSpans("task-span-1", 25);

        assertEquals(0, response.getCode());
        assertEquals("ok", response.getMessage());
        assertEquals(1, response.getData().size());
        GenerationTaskSpanVO span = response.getData().getFirst();
        assertEquals("span-1", span.getSpanId());
        assertEquals("task-span-1", span.getTaskId());
        assertEquals("pnpm_install", span.getStage());
        assertEquals("DEPENDENCY", span.getCategory());
        assertEquals("success", span.getStatus());
        assertEquals(startedAt, span.getStartedAt());
        assertEquals(endedAt, span.getEndedAt());
        assertEquals(12_000L, span.getDurationMs());
        assertEquals("exitCode=0", span.getDetail());
        verify(queryService).findByTaskId("task-span-1", 25);
    }

    @Test
    void taskSpanEndpointMustRemainAdministratorOnly() throws NoSuchMethodException {
        Method method = GenerationPerformanceController.class
                .getMethod("getTaskSpans", String.class, Integer.class);

        AuthCheck authCheck = method.getAnnotation(AuthCheck.class);

        assertNotNull(authCheck);
        assertEquals(UserConstant.ADMIN_ROLE, authCheck.mustRole());
    }
    @Test
    void getRouteDurationProfileMustExposeCachedHistoricalPercentiles() {
        GenerationPerformanceMonitorService monitorService = mock(GenerationPerformanceMonitorService.class);
        GenerationSpanQueryService queryService = mock(GenerationSpanQueryService.class);
        GenerationDurationProfileService profileService = mock(GenerationDurationProfileService.class);
        Instant computedAt = Instant.parse("2026-07-16T09:00:00Z");
        when(profileService.getProfile("heavy")).thenReturn(new GenerationDurationProfile(
                "heavy", 20, 600_000L, 900_000L, 1_200_000L,
                List.of(new GenerationStageDurationProfile(
                        "build", "build", 18, 120_000L, 240_000L, 300_000L)),
                computedAt));
        GenerationPerformanceController controller = new GenerationPerformanceController(
                monitorService, queryService, profileService,
                mock(GenerationTaskLatencyLedgerService.class),
                mock(GenerationRouteLatencySegmentService.class),
                promotionService());

        BaseResponse<GenerationDurationProfileVO> response = controller.getRouteDurationProfile("heavy");

        assertEquals(20, response.getData().taskSampleSize());
        assertEquals(600_000L, response.getData().p50TotalDurationMs());
        assertEquals(1, response.getData().stages().size());
        assertEquals("build", response.getData().stages().getFirst().stage());
        assertEquals(computedAt, response.getData().computedAt());
        verify(profileService).getProfile("heavy");
    }

    @Test
    void routeDurationProfileEndpointMustRemainAdministratorOnly() throws NoSuchMethodException {
        Method method = GenerationPerformanceController.class
                .getMethod("getRouteDurationProfile", String.class);

        AuthCheck authCheck = method.getAnnotation(AuthCheck.class);

        assertNotNull(authCheck);
        assertEquals(UserConstant.ADMIN_ROLE, authCheck.mustRole());
    }

    @Test
    void getTaskLatencyLedgerMustExposeCriticalPathAndCoverageDiagnostics() {
        GenerationTaskLatencyLedgerService ledgerService = mock(GenerationTaskLatencyLedgerService.class);
        GenerationPerformanceController controller = new GenerationPerformanceController(
                mock(GenerationPerformanceMonitorService.class),
                mock(GenerationSpanQueryService.class),
                mock(GenerationDurationProfileService.class),
                ledgerService,
                mock(GenerationRouteLatencySegmentService.class),
                promotionService()
        );
        Instant submittedAt = Instant.parse("2026-07-18T01:00:00Z");
        when(ledgerService.getLedger("task-ledger-1")).thenReturn(new GenerationTaskLatencyLedger(
                "task-ledger-1", 1L, 2L, "heavy_generation", "success", "completed",
                submittedAt, submittedAt.plusSeconds(90), submittedAt.plusSeconds(100),
                submittedAt.plusSeconds(101), 100_000L, 90_000L, 10_000L, 90.0d,
                20_000L, 10_000L, 3, 3, false, "model",
                List.of(new GenerationTaskLatencyLedger.CategoryLatency(
                        "model", 1, 60_000L, 60_000L, 60.0d))
        ));

        var response = controller.getTaskLatencyLedger("task-ledger-1");

        assertEquals(0, response.getCode());
        assertEquals(100_000L, response.getData().totalLatencyMs());
        assertEquals(90.0d, response.getData().attributionCoveragePercent());
        assertEquals("model", response.getData().dominantCategory());
        assertEquals(60_000L, response.getData().categories().getFirst().attributedDurationMs());
        verify(ledgerService).getLedger("task-ledger-1");
    }

    @Test
    void taskLatencyLedgerEndpointMustRemainAdministratorOnly() throws NoSuchMethodException {
        Method method = GenerationPerformanceController.class
                .getMethod("getTaskLatencyLedger", String.class);

        AuthCheck authCheck = method.getAnnotation(AuthCheck.class);

        assertNotNull(authCheck);
        assertEquals(UserConstant.ADMIN_ROLE, authCheck.mustRole());
    }

    private GenerationStrategyPromotionService promotionService() {
        return new GenerationStrategyPromotionService(
                mock(GenerationScenarioAttributionRepository.class),
                new GenerationStrategyPromotionGate(new GenerationBenchmarkReleaseProperties()));
    }

}
