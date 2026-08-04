package com.rush.rushaicodemother.orchestration.pipeline;

import com.rush.rushaicodemother.core.handler.GenerationStreamEvent;
import com.rush.rushaicodemother.infrastructure.diagnostic.LogExceptionSanitizer;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.enums.GenerationTaskStatus;
import com.rush.rushaicodemother.monitor.GenerationPerformanceMonitorService;
import com.rush.rushaicodemother.monitor.span.GenerationSpanCategory;
import com.rush.rushaicodemother.orchestration.GenerationSession;
import com.rush.rushaicodemother.orchestration.attempt.completion.GenerationCompletionEvidenceSet;
import com.rush.rushaicodemother.orchestration.edit.LightweightEditResult;
import com.rush.rushaicodemother.orchestration.edit.LightweightEditService;
import com.rush.rushaicodemother.orchestration.router.GenerationMode;
import com.rush.rushaicodemother.orchestration.routing.GenerationRoute;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionPolicyException;
import com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskExecution;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 轻量编辑生成处理流水线。
 */
@Slf4j
@Order(10)
@Component
@RequiredArgsConstructor
public class LightweightEditGenerationPipeline implements GenerationPipeline {

    private static final String LIGHTWEIGHT_EDIT_FAILURE_REASON = "lightweight_edit_failed";

    private final GenerationPerformanceMonitorService generationPerformanceMonitorService;
    private final LightweightEditService lightweightEditService;

    @Override
    public String route() {
        return GenerationRoute.LIGHTWEIGHT_EDIT;
    }

    @Override
    public boolean supports(GenerationPipelineRequest request) {
        return request.modeIs(GenerationMode.LIGHT_EDIT);
    }

    /**
 * 执行轻量编辑生成流水线处理流程。
 *
 * @param request 请求参数
 * @return 轻量编辑生成流水线
 */
    @Override
    public GenerationPipelineOutcome execute(GenerationPipelineRequest request) {
        GenerationTaskExecution execution = request.requireExecution();
        GenerationSession session = execution.session();
        App app = request.taskRequest().app();
        Instant startedAt = Instant.now();
        generationPerformanceMonitorService.startTask(
                execution.taskId(),
                app.getId(),
                request.taskRequest().loginUser().getId(),
                route(),
                request.codeGenType().getValue(),
                startedAt,
                request.modeDecision()
        );
        // 将可能失败的操作收敛在统一异常边界内，便于清理资源和转换错误。
        try {
            session.throwIfCancelled();
            LightweightEditResult editResult = request.executionPlan() == null
                    ? lightweightEditService.execute(
                            execution.taskId(), request.taskRequest(), request.workspace())
                    : lightweightEditService.execute(
                            execution.taskId(),
                            request.taskRequest(),
                            request.workspace(),
                            request.executionPlan());
            if (editResult == null) {
                return GenerationPipelineOutcome.fallback(route(), "lightweight_edit_not_applicable");
            }
            assertTaskIdentity(execution.taskId(), editResult.taskId());
            String status = "failed".equals(editResult.validationResult()) ? "failed" : "success";
            generationPerformanceMonitorService.recordSpan(
                    execution.taskId(),
                    "lightweight_edit",
                    GenerationSpanCategory.PIPELINE,
                    status,
                    Duration.between(startedAt, Instant.now()),
                    editResult.route()
            );
            if ("failed".equals(status)) {
                session.emit(GenerationStreamEvent.generationError(
                        editResult.summary(),
                        Map.of(
                                "route", editResult.route(),
                                "taskId", execution.taskId(),
                                "status", status
                        )
                ));
            } else {
                session.emit(GenerationStreamEvent.agentEvent(
                        editResult.summary(),
                        Map.of(
                                "route", editResult.route(),
                                "mode", request.modeDecision().mode().name(),
                                "routerReason", request.modeDecision().reason(),
                                "taskId", execution.taskId(),
                                "status", editResult.validationResult()
                        )
                ));
            }
            generationPerformanceMonitorService.finishTask(execution.taskId(), status);
            log.info("轻量编辑路径完成，appId: {}, taskId: {}, route: {}, status: {}",
                    app.getId(), execution.taskId(), editResult.route(), status);
            return GenerationPipelineOutcome.completed(
                    route(),
                    "success".equals(status) ? GenerationTaskStatus.SUCCESS : GenerationTaskStatus.FAILED,
                    "success".equals(status) ? null : LIGHTWEIGHT_EDIT_FAILURE_REASON,
                    buildResultSummary("success".equals(status) ? "成功" : "失败", editResult)
            );
        } catch (GenerationExecutionPolicyException executionPolicyFailure) {
            throw executionPolicyFailure;
        } catch (RuntimeException failure) {
            log.warn("轻量编辑路径执行失败，appId: {}, taskId: {}, error: {}",
                    app.getId(), execution.taskId(), LogExceptionSanitizer.sanitizeMessage(failure));
            session.emit(GenerationStreamEvent.generationError(
                    "轻量编辑执行失败，请稍后重试",
                    Map.of("route", route(), "taskId", execution.taskId(), "status", "failed")
            ));
            generationPerformanceMonitorService.finishTask(execution.taskId(), "failed");
            return GenerationPipelineOutcome.completed(
                    route(),
                    GenerationTaskStatus.FAILED,
                    LIGHTWEIGHT_EDIT_FAILURE_REASON,
                    "任务状态：失败\n执行路径：LIGHT_EDIT\n失败原因：轻量编辑执行失败，请稍后重试"
            );
        }
    }

    /** 构建并返回结果汇总。 */
    private String buildResultSummary(String status, LightweightEditResult result) {
        List<String> operations = result.appliedOperations() == null
                ? List.of()
                : result.appliedOperations();
        String changedFiles = String.join(", ", operations.stream().limit(30).toList());
        return "任务状态：" + status
                + "\n执行路径：LIGHT_EDIT"
                + "\n结果摘要：" + safeText(result.summary(), "未提供")
                + "\n修改文件数量：" + operations.size()
                + "\n修改文件：" + safeText(changedFiles, "无")
                + "\n验证结果：" + safeText(result.validationResult(), "未知");
    }

    private String safeText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private void assertTaskIdentity(String expectedTaskId, String actualTaskId) {
        if (!expectedTaskId.equals(actualTaskId)) {
            throw new IllegalStateException("lightweight edit returned a different taskId");
        }
    }
}
