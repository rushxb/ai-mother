package com.rush.rushaicodemother.orchestration.pipeline;

import com.rush.rushaicodemother.core.handler.GenerationStreamEvent;
import com.rush.rushaicodemother.infrastructure.diagnostic.LogExceptionSanitizer;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.model.enums.GenerationTaskStatus;
import com.rush.rushaicodemother.monitor.GenerationPerformanceMonitorService;
import com.rush.rushaicodemother.monitor.span.GenerationSpanCategory;
import com.rush.rushaicodemother.orchestration.GenerationSession;
import com.rush.rushaicodemother.orchestration.attempt.completion.GenerationCompletionEvidenceSet;
import com.rush.rushaicodemother.orchestration.edit.AgentEditGenerationService;
import com.rush.rushaicodemother.orchestration.edit.AgentEditResult;
import com.rush.rushaicodemother.orchestration.intent.IntentOperationType;
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
import java.util.EnumSet;
import java.util.Map;

/**
 * 智能体编辑生成处理流水线。
 */
@Slf4j
@Order(30)
@Component
@RequiredArgsConstructor
public class AgentEditGenerationPipeline implements GenerationPipeline {

    private static final GenerationPipelineCapability CAPABILITY =
            GenerationPipelineCapability.write(
                    GenerationRoute.AGENT_EDIT,
                    EnumSet.of(IntentOperationType.EDIT, IntentOperationType.REPAIR),
                    EnumSet.allOf(CodeGenTypeEnum.class),
                    EnumSet.of(GenerationMode.AGENT_EDIT));
    private static final String AGENT_EDIT_FAILURE_REASON = "agent_edit_failed";

    private final AgentEditGenerationService agentEditGenerationService;
    private final GenerationPerformanceMonitorService generationPerformanceMonitorService;

    @Override
    public String route() {
        return CAPABILITY.route();
    }

    @Override
    public GenerationPipelineCapability capability() {
        return CAPABILITY;
    }

    /**
 * 执行智能体编辑生成流水线处理流程。
 *
 * @param request 请求参数
 * @return 智能体编辑生成流水线
 */
    @Override
    public GenerationPipelineOutcome execute(GenerationPipelineRequest request) {
        GenerationTaskExecution execution = request.requireExecution();
        GenerationSession session = execution.session();
        App app = request.taskRequest().app();
        Instant startedAt = Instant.now();
        // 将可能失败的操作收敛在统一异常边界内，便于清理资源和转换错误。
        try {
            session.throwIfCancelled();
            AgentEditResult editResult = request.executionPlan() == null
                    ? agentEditGenerationService.execute(
                            execution.taskId(),
                            request.taskRequest(),
                            request.modeDecision(),
                            request.workspace())
                    : agentEditGenerationService.execute(
                            execution.taskId(),
                            request.taskRequest(),
                            request.modeDecision(),
                            request.workspace(),
                            request.executionPlan());
            if (editResult == null) {
                return GenerationPipelineOutcome.fallback(route(), "agent_edit_not_applicable");
            }
            assertTaskIdentity(execution.taskId(), editResult.taskId());
            generationPerformanceMonitorService.recordSpan(
                    execution.taskId(),
                    "agent_edit_pipeline",
                    GenerationSpanCategory.PIPELINE,
                    editResult.status(),
                    Duration.between(startedAt, Instant.now()),
                    "repairRounds=" + editResult.repairRounds()
            );
            if ("failed".equals(editResult.status())) {
                session.emit(GenerationStreamEvent.generationError(
                        editResult.summary(),
                        Map.of(
                                "route", editResult.route(),
                                "mode", request.modeDecision().mode().name(),
                                "routerReason", request.modeDecision().reason(),
                                "taskId", execution.taskId(),
                                "status", editResult.status(),
                                "repairRounds", editResult.repairRounds()
                        )
                ));
                generationPerformanceMonitorService.finishTask(execution.taskId(), "failed");
                return GenerationPipelineOutcome.completed(
                        route(),
                        GenerationTaskStatus.FAILED,
                        AGENT_EDIT_FAILURE_REASON,
                        buildResultSummary("失败", editResult),
                        GenerationCompletionEvidenceSet.empty(),
                        editResult.changedFiles().size(),
                        editResult.repairRounds()
                );
            }
            session.emit(GenerationStreamEvent.agentEvent(
                    editResult.summary(),
                    Map.of(
                            "route", editResult.route(),
                            "mode", request.modeDecision().mode().name(),
                            "routerReason", request.modeDecision().reason(),
                            "taskId", execution.taskId(),
                            "status", editResult.status(),
                            "repairRounds", editResult.repairRounds()
                    )
            ));
            generationPerformanceMonitorService.finishTask(execution.taskId(), "success");
            return GenerationPipelineOutcome.completed(
                    route(),
                    GenerationTaskStatus.SUCCESS,
                    null,
                    buildResultSummary("成功", editResult),
                    GenerationCompletionEvidenceSet.successfulMutation(
                            request.modeDecision().expectedValidationLevel(),
                            route(),
                            editResult.changedFiles().size()),
                    editResult.changedFiles().size(),
                    editResult.repairRounds()
            );
        } catch (GenerationExecutionPolicyException executionPolicyFailure) {
            throw executionPolicyFailure;
        } catch (RuntimeException failure) {
            log.warn("AGENT_EDIT 路径执行失败，appId: {}, taskId: {}, error: {}",
                    app.getId(), execution.taskId(), LogExceptionSanitizer.sanitizeMessage(failure));
            session.emit(GenerationStreamEvent.generationError(
                    "智能编辑执行失败，请稍后重试",
                    Map.of("route", route(), "taskId", execution.taskId(), "status", "failed")
            ));
            generationPerformanceMonitorService.finishTask(execution.taskId(), "failed");
            return GenerationPipelineOutcome.completed(
                    route(),
                    GenerationTaskStatus.FAILED,
                    AGENT_EDIT_FAILURE_REASON,
                    "任务状态：失败\n执行路径：AGENT_EDIT\n失败原因：智能编辑执行失败，请稍后重试"
            );
        }
    }

    /** 构建并返回结果汇总。 */
    private String buildResultSummary(String status, AgentEditResult result) {
        String changedFiles = result.changedFiles().stream()
                .limit(30)
                .reduce((left, right) -> left + ", " + right)
                .orElse("无");
        return "任务状态：" + status
                + "\n执行路径：AGENT_EDIT"
                + "\n结果摘要：" + result.summary()
                + "\n修改文件数量：" + result.changedFiles().size()
                + "\n修改文件：" + changedFiles
                + "\n修复轮次：" + result.repairRounds();
    }

    private void assertTaskIdentity(String expectedTaskId, String actualTaskId) {
        if (!expectedTaskId.equals(actualTaskId)) {
            throw new IllegalStateException("agent edit returned a different taskId");
        }
    }
}
