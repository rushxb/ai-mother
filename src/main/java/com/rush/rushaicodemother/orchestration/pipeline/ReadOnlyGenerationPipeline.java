package com.rush.rushaicodemother.orchestration.pipeline;

import com.rush.rushaicodemother.core.handler.GenerationStreamEvent;
import com.rush.rushaicodemother.infrastructure.diagnostic.LogExceptionSanitizer;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.enums.GenerationTaskStatus;
import com.rush.rushaicodemother.monitor.GenerationPerformanceMonitorService;
import com.rush.rushaicodemother.orchestration.GenerationPreparation;
import com.rush.rushaicodemother.orchestration.GenerationSession;
import com.rush.rushaicodemother.orchestration.artifact.GenerationArtifact;
import com.rush.rushaicodemother.orchestration.attempt.completion.GenerationCompletionEvidence;
import com.rush.rushaicodemother.orchestration.attempt.completion.GenerationCompletionEvidenceSet;
import com.rush.rushaicodemother.orchestration.attempt.completion.GenerationCompletionEvidenceType;
import com.rush.rushaicodemother.orchestration.readonly.ReadOnlyAnalysisResult;
import com.rush.rushaicodemother.orchestration.readonly.ReadOnlyAnalysisService;
import com.rush.rushaicodemother.orchestration.router.GenerationMode;
import com.rush.rushaicodemother.orchestration.routing.GenerationRoute;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionPolicyException;
import com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskExecution;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * EXPLAIN、AUDIT 与 PLAN 的零副作用执行路径。
 *
 * <p>该流水线只依赖 {@link ReadOnlyAnalysisService} 深模块，不注入补丁、工具、
 * 构建或发布服务，从依赖结构上消除误写工作区的可能性。</p>
 */
@Slf4j
@Order(5)
@Component
public class ReadOnlyGenerationPipeline implements GenerationPipeline {

    public static final String ANALYSIS_ARTIFACT = "analysis";
    public static final String NO_CHANGE_JUSTIFICATION_ARTIFACT = "no_change_justification";
    private static final String FAILURE_REASON = "read_only_analysis_failed";

    private final GenerationPerformanceMonitorService performanceMonitorService;
    private final ReadOnlyAnalysisService analysisService;

    public ReadOnlyGenerationPipeline(GenerationPerformanceMonitorService performanceMonitorService,
                                      ReadOnlyAnalysisService analysisService) {
        this.performanceMonitorService = Objects.requireNonNull(
                performanceMonitorService, "生成性能监控服务不能为空");
        this.analysisService = Objects.requireNonNull(analysisService, "只读分析服务不能为空");
    }

    @Override
    public String route() {
        return GenerationRoute.READ_ONLY;
    }

    @Override
    public boolean supports(GenerationPipelineRequest request) {
        return request != null && request.modeIs(GenerationMode.READ_ONLY);
    }

    @Override
    public GenerationPipelineOutcome execute(GenerationPipelineRequest request) {
        GenerationTaskExecution execution = request.requireExecution();
        GenerationSession session = execution.session();
        App app = request.taskRequest().app();
        Instant startedAt = Instant.now();
        performanceMonitorService.startTask(
                execution.taskId(),
                app.getId(),
                request.taskRequest().loginUser().getId(),
                route(),
                request.codeGenType().getValue(),
                startedAt,
                request.modeDecision());
        try {
            session.throwIfCancelled();
            ReadOnlyAnalysisResult analysis = analysisService.analyze(
                    execution.taskId(),
                    request.intentProfile().operationType(),
                    request.taskRequest().message(),
                    request.workspace(),
                    request.codeGenType());
            session.throwIfCancelled();
            GenerationPreparation preparation = createPreparation(request, execution.taskId(), analysis);
            session.bindPreparation(preparation);
            String report = analysis.renderMarkdown();
            session.emit(GenerationStreamEvent.aiDelta(report));
            performanceMonitorService.finishTask(execution.taskId(), "success");
            return GenerationPipelineOutcome.completed(
                    route(),
                    GenerationTaskStatus.SUCCESS,
                    null,
                    report,
                    completionEvidence(analysis),
                    0,
                    0);
        } catch (GenerationExecutionPolicyException policyFailure) {
            throw policyFailure;
        } catch (RuntimeException failure) {
            log.warn("只读分析执行失败，appId: {}, taskId: {}, error: {}",
                    app.getId(), execution.taskId(), LogExceptionSanitizer.sanitizeMessage(failure));
            session.emit(GenerationStreamEvent.generationError(
                    "只读分析失败，请稍后重试",
                    Map.of("route", route(), "taskId", execution.taskId(), "status", "failed")));
            performanceMonitorService.finishTask(execution.taskId(), "failed");
            return GenerationPipelineOutcome.completed(
                    route(),
                    GenerationTaskStatus.FAILED,
                    FAILURE_REASON,
                    "任务状态：失败\n执行路径：READ_ONLY\n失败原因：只读分析执行失败，请稍后重试");
        }
    }

    private GenerationPreparation createPreparation(GenerationPipelineRequest request,
                                                     String taskId,
                                                     ReadOnlyAnalysisResult analysis) {
        Map<String, GenerationArtifact> artifacts = new LinkedHashMap<>();
        Map<String, Object> analysisPayload = new LinkedHashMap<>(analysis.toPayload());
        analysisPayload.put("operationType", request.intentProfile().operationType().name());
        artifacts.put(ANALYSIS_ARTIFACT, GenerationArtifact.of(
                ANALYSIS_ARTIFACT,
                "ReadOnlyAnalyst",
                "只读分析报告",
                analysisPayload));
        artifacts.put(NO_CHANGE_JUSTIFICATION_ARTIFACT, GenerationArtifact.of(
                NO_CHANGE_JUSTIFICATION_ARTIFACT,
                "ExecutionPolicy",
                "未修改工作区说明",
                Map.of(
                        "justification", analysis.noChangeJustification(),
                        "workspacePublished", false,
                        "toolWriteCount", 0,
                        "buildCount", 0)));
        return new GenerationPreparation(
                request.codeGenType(),
                request.codeGenType(),
                false,
                "read_only_analysis",
                request.taskRequest().message(),
                List.of(),
                artifacts,
                null,
                Map.of(),
                taskId);
    }

    private GenerationCompletionEvidenceSet completionEvidence(ReadOnlyAnalysisResult analysis) {
        return GenerationCompletionEvidenceSet.of(
                GenerationCompletionEvidence.of(
                        GenerationCompletionEvidenceType.INTENT_COVERAGE,
                        route(),
                        "已按冻结的只读意图完成分析"),
                GenerationCompletionEvidence.of(
                        GenerationCompletionEvidenceType.NO_CHANGE_JUSTIFICATION,
                        route(),
                        analysis.noChangeJustification()),
                GenerationCompletionEvidence.of(
                        GenerationCompletionEvidenceType.FAST_VALIDATION,
                        route(),
                        "分析文件引用已通过采集上下文校验"));
    }
}
