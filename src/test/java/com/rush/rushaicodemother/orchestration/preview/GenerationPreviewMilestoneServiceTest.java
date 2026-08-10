package com.rush.rushaicodemother.orchestration.preview;

import com.rush.rushaicodemother.core.handler.GenerationStreamEvent;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.monitor.GenerationOrchestrationMetricsCollector;
import com.rush.rushaicodemother.monitor.GenerationPerformanceMonitorService;
import com.rush.rushaicodemother.orchestration.GenerationSession;
import com.rush.rushaicodemother.orchestration.GenerationTaskRequest;
import com.rush.rushaicodemother.orchestration.event.GenerationEventPublisher;
import com.rush.rushaicodemother.orchestration.event.GenerationEventType;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationBudgetKind;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContext;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionLimits;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class GenerationPreviewMilestoneServiceTest {

    @Test
    void publishesFirstRuntimePreviewExactlyOnce() {
        GenerationPerformanceMonitorService performance = mock(GenerationPerformanceMonitorService.class);
        GenerationOrchestrationMetricsCollector metrics = mock(GenerationOrchestrationMetricsCollector.class);
        GenerationEventPublisher publisher = mock(GenerationEventPublisher.class);
        GenerationPreviewMilestoneService service =
                new GenerationPreviewMilestoneService(performance, metrics, publisher);
        GenerationSession session = session();

        assertTrue(service.publishRuntimeReady(session, CodeGenTypeEnum.VUE_PROJECT));
        assertFalse(service.publishRuntimeReady(session, CodeGenTypeEnum.VUE_PROJECT));

        GenerationStreamEvent event = session.asFlux().blockFirst(Duration.ofSeconds(1));
        assertEquals(GenerationStreamEvent.FIRST_PREVIEW_READY, event.getType());
        assertEquals("runtime", event.getData().get("previewLevel"));
        verify(publisher, times(1)).publishSafely(
                any(GenerationTaskRequest.class), eq(GenerationEventType.FIRST_PREVIEW_READY), any(), any());
        verify(metrics, times(1)).recordSlaOutcome(
                "create", "first_preview", "met", "within_deadline");
    }

    @Test
    void publishesBackendBuildArtifactMilestoneWithoutClaimingRuntimeReadiness() {
        GenerationPreviewMilestoneService service = new GenerationPreviewMilestoneService(
                mock(GenerationPerformanceMonitorService.class),
                mock(GenerationOrchestrationMetricsCollector.class),
                mock(GenerationEventPublisher.class)
        );
        GenerationSession session = session();

        assertTrue(service.publishBuildReady(session, CodeGenTypeEnum.BACKEND_PROJECT));

        GenerationStreamEvent event = session.asFlux().blockFirst(Duration.ofSeconds(1));
        assertEquals("build", event.getData().get("previewLevel"));
        assertEquals("首个可用构建产物已就绪", event.getText());
    }

    @Test
    void provisionalPreviewMustBeMarkedUnverifiedAndNotConsumeVerifiedMilestone() {
        GenerationOrchestrationMetricsCollector metrics = mock(GenerationOrchestrationMetricsCollector.class);
        GenerationPreviewMilestoneService service = new GenerationPreviewMilestoneService(
                mock(GenerationPerformanceMonitorService.class),
                metrics,
                mock(GenerationEventPublisher.class)
        );
        GenerationSession session = session();

        assertTrue(service.publishProvisionalReady(session, CodeGenTypeEnum.VUE_PROJECT));
        // 暂定预览幂等：同一执行纪元内只通知一次。
        assertFalse(service.publishProvisionalReady(session, CodeGenTypeEnum.VUE_PROJECT));
        // 关键：暂定预览不得占用已验证里程碑，交付语义仍需独立发布。
        assertTrue(service.publishRuntimeReady(session, CodeGenTypeEnum.VUE_PROJECT));

        GenerationStreamEvent provisional = session.asFlux().blockFirst(Duration.ofSeconds(1));
        assertEquals("provisional", provisional.getData().get("previewLevel"));
        assertEquals(Boolean.FALSE, provisional.getData().get("verified"));
        // SLA 结论每任务仅一条，由暂定预览裁定，避免同一任务出现两条互相矛盾的结论。
        verify(metrics, times(1)).recordSlaOutcome(
                "create", "first_preview", "met", "within_deadline");
    }

    /**
     * 两级预览必须落在不同的指标序列与不同的 span 阶段上。
     *
     * <p>同一个 EXPERT 任务会先后产生暂定与已验证两个样本。若指标不带 preview_level 标签，
     * 二者会混进同一条时序，直方图变成双峰分布，分位数与告警阈值都失去意义 ——
     * 而 P0-2 立项的理由正是「TTP 指标失真」。</p>
     */
    @Test
    void provisionalAndVerifiedPreviewsMustNotShareTheSameMetricSeries() {
        GenerationOrchestrationMetricsCollector metrics = mock(GenerationOrchestrationMetricsCollector.class);
        GenerationPerformanceMonitorService performance = mock(GenerationPerformanceMonitorService.class);
        GenerationPreviewMilestoneService service = new GenerationPreviewMilestoneService(
                performance, metrics, mock(GenerationEventPublisher.class));
        GenerationSession session = session();

        service.publishProvisionalReady(session, CodeGenTypeEnum.VUE_PROJECT);
        service.publishRuntimeReady(session, CodeGenTypeEnum.VUE_PROJECT);

        verify(metrics, times(1)).recordFirstPreviewDuration(
                eq("create"), eq("vue_project"), any(), eq("provisional"), any());
        verify(metrics, times(1)).recordFirstPreviewDuration(
                eq("create"), eq("vue_project"), any(), eq("verified"), any());
        // span 阶段名与指标标签必须同口径，否则仪表盘与 span 会给出两个互相矛盾的结论。
        verify(performance, times(1)).recordSpan(
                any(), eq("time_to_provisional_preview"), any(), any(), any(), any());
        verify(performance, times(1)).recordSpan(
                any(), eq("time_to_first_preview"), any(), any(), any(), any());
    }

    @Test
    void verifiedPreviewMustReportSlaWhenNoProvisionalPreviewHappened() {
        GenerationOrchestrationMetricsCollector metrics = mock(GenerationOrchestrationMetricsCollector.class);
        GenerationPreviewMilestoneService service = new GenerationPreviewMilestoneService(
                mock(GenerationPerformanceMonitorService.class),
                metrics,
                mock(GenerationEventPublisher.class)
        );
        GenerationSession session = session();

        // LIGHT_EDIT、HTML 等链路不产生暂定预览，SLA 指标不能因此缺失。
        assertTrue(service.publishRuntimeReady(session, CodeGenTypeEnum.VUE_PROJECT));

        GenerationStreamEvent event = session.asFlux().blockFirst(Duration.ofSeconds(1));
        assertEquals(Boolean.TRUE, event.getData().get("verified"));
        verify(metrics, times(1)).recordSlaOutcome(
                "create", "first_preview", "met", "within_deadline");
    }

    private GenerationSession session() {
        EnumMap<GenerationBudgetKind, Integer> budgets = new EnumMap<>(GenerationBudgetKind.class);
        for (GenerationBudgetKind kind : GenerationBudgetKind.values()) {
            budgets.put(kind, 2);
        }
        GenerationExecutionContext context = new GenerationExecutionContext(
                "preview-task", 1L, 2L, Instant.parse("2026-07-17T00:00:00Z"),
                new GenerationExecutionLimits(
                        Duration.ofMinutes(10), Duration.ofMinutes(2), Duration.ofMillis(500), budgets),
                Clock.fixed(Instant.parse("2026-07-17T00:00:30Z"), ZoneOffset.UTC));
        App app = new App();
        app.setId(1L);
        User user = new User();
        user.setId(2L);
        GenerationSession session = new GenerationSession(null, context);
        session.bindTaskRequest(new GenerationTaskRequest(app, "build", user));
        session.recordRoute("create");
        return session;
    }
}
