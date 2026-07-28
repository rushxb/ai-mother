package com.rush.rushaicodemother.orchestration.preview;

import com.rush.rushaicodemother.core.handler.GenerationStreamEvent;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.monitor.GenerationOrchestrationMetricsCollector;
import com.rush.rushaicodemother.monitor.GenerationPerformanceMonitorService;
import com.rush.rushaicodemother.monitor.span.GenerationSpanCategory;
import com.rush.rushaicodemother.orchestration.GenerationSession;
import com.rush.rushaicodemother.orchestration.event.GenerationEventPublisher;
import com.rush.rushaicodemother.orchestration.event.GenerationEventType;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContext;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationFirstPreviewMilestone;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/** 首个用户可见版本发布后，幂等发送预览事件并记录 SLA 结果。 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GenerationPreviewMilestoneService {

    private final GenerationPerformanceMonitorService performanceMonitorService;
    private final GenerationOrchestrationMetricsCollector metricsCollector;
    private final GenerationEventPublisher eventPublisher;

    public boolean publishRuntimeReady(GenerationSession session, CodeGenTypeEnum targetType) {
        return publishReady(session, targetType, "runtime", "首个可运行预览已就绪");
    }

    public boolean publishBuildReady(GenerationSession session, CodeGenTypeEnum targetType) {
        return publishReady(session, targetType, "build", "首个可用构建产物已就绪");
    }

    /** 发布就绪。 */
    private boolean publishReady(
            GenerationSession session,
            CodeGenTypeEnum targetType,
            String previewLevel,
            String message
    ) {
        if (session == null || session.executionContext() == null) {
            return false;
        }
        GenerationExecutionContext context = session.executionContext();
        GenerationFirstPreviewMilestone milestone = context.markFirstPreviewReady();
        if (!milestone.firstPublication()) {
            return false;
        }
        String route = normalize(session.route(), "unknown");
        String target = targetType == null ? "unknown" : targetType.getValue();
        String slaStatus = milestone.slaBreached() ? "breached" : "met";
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("taskId", context.taskId());
        data.put("route", route);
        data.put("targetType", target);
        data.put("previewLevel", previewLevel);
        data.put("elapsedMs", milestone.elapsed().toMillis());
        data.put("slaStatus", slaStatus);
        data.put("slaProfile", context.slaProfile());
        data.put("firstPreviewDeadlineAt", milestone.deadlineAt().toString());

        session.emit(GenerationStreamEvent.firstPreviewReady(message, Map.copyOf(data)));
        eventPublisher.publishSafely(
                session.taskRequest(), GenerationEventType.FIRST_PREVIEW_READY,
                message, Map.copyOf(data));
        recordTelemetry(context, milestone, route, target, slaStatus);
        return true;
    }

    /** 记录遥测相关指标或状态。 */
    private void recordTelemetry(GenerationExecutionContext context,
                                 GenerationFirstPreviewMilestone milestone,
                                 String route,
                                 String target,
                                 String slaStatus) {
        try {
            performanceMonitorService.recordSpan(
                    context.taskId(), "time_to_first_preview", GenerationSpanCategory.PIPELINE,
                    slaStatus, milestone.elapsed(), context.slaProfile());
            metricsCollector.recordFirstPreviewDuration(route, target, slaStatus, milestone.elapsed());
            metricsCollector.recordSlaOutcome(
                    route, "first_preview", slaStatus,
                    milestone.slaBreached() ? "deadline_exceeded" : "within_deadline");
        } catch (RuntimeException telemetryFailure) {
            log.warn("首预览遥测记录失败，生成流程继续执行，taskId: {}",
                    context.taskId(), telemetryFailure);
        }
    }

    private String normalize(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
