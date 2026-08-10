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
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationPreviewLevel;
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

    /**
     * 发布「暂定可预览」里程碑：工作区已可渲染，但尚未通过验证。
     *
     * <p>只发事件与遥测，<b>不参与完成证据、不触发计费、不写任务终态</b> —— 这些仍由
     * {@link #publishRuntimeReady} / {@link #publishBuildReady} 所在的已验证收口链路负责。
     * 因此暂定预览失败不会影响交付，用户却能提前看到结果。</p>
     *
     * @return 是否为本执行纪元的首次暂定预览发布
     */
    public boolean publishProvisionalReady(GenerationSession session, CodeGenTypeEnum targetType) {
        if (session == null || session.executionContext() == null) {
            return false;
        }
        GenerationExecutionContext context = session.executionContext();
        GenerationFirstPreviewMilestone milestone = context.markProvisionalPreviewReady();
        return emitMilestone(
                session, targetType, context, milestone,
                GenerationPreviewLevel.PROVISIONAL.wireValue(),
                "首个可预览版本已就绪（尚未完成验证）");
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
        return emitMilestone(session, targetType, context, milestone, previewLevel, message);
    }

    /** 组装并发送里程碑事件；非首次发布直接跳过，保证幂等。 */
    private boolean emitMilestone(
            GenerationSession session,
            CodeGenTypeEnum targetType,
            GenerationExecutionContext context,
            GenerationFirstPreviewMilestone milestone,
            String previewLevel,
            String message
    ) {
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
        // previewLevel 表达「由什么机制就绪」（runtime/build/provisional），是既有契约不便变更语义；
        // verified 显式表达「是否已通过验证」，供前端区分「先看到」与「可交付」两种状态。
        data.put("verified", !milestone.provisional());
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
            // 两级预览分别记 span，避免暂定预览污染既有「已验证首预览」时序基线。
            String stage = milestone.provisional()
                    ? "time_to_provisional_preview"
                    : "time_to_first_preview";
            performanceMonitorService.recordSpan(
                    context.taskId(), stage, GenerationSpanCategory.PIPELINE,
                    slaStatus, milestone.elapsed(), context.slaProfile());
            metricsCollector.recordFirstPreviewDuration(route, target, slaStatus, milestone.elapsed());
            // SLA 结论每个任务只出一条，否则同一任务会产生两条互相矛盾的 first_preview 结论。
            // 优先由暂定预览裁定（它才是「用户多久看到东西」，也是首预览截止线的原意）；
            // 未发生暂定预览的链路（如 LIGHT_EDIT、HTML）仍由已验证预览兜底上报，避免指标缺失。
            boolean provisionalAlreadyReported = context.firstProvisionalPreviewAt() != null;
            if (milestone.provisional() || !provisionalAlreadyReported) {
                metricsCollector.recordSlaOutcome(
                        route, "first_preview", slaStatus,
                        milestone.slaBreached() ? "deadline_exceeded" : "within_deadline");
            }
        } catch (RuntimeException telemetryFailure) {
            log.warn("首预览遥测记录失败，生成流程继续执行，taskId: {}",
                    context.taskId(), telemetryFailure);
        }
    }

    private String normalize(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
