package com.rush.rushaicodemother.orchestration.readonly;

import com.rush.rushaicodemother.monitor.GenerationPerformanceMonitorService;
import com.rush.rushaicodemother.monitor.span.GenerationSpanCategory;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationBudgetKind;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContext;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContextService;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionPolicyException;
import com.rush.rushaicodemother.orchestration.runtime.model.GenerationSynchronousModelCallSupervisor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Objects;

/** 将只读分析模型调用接入任务截止时间、取消状态和模型预算。 */
@Component
class ManagedReadOnlyAnalysisModel implements ReadOnlyAnalysisModel {

    private static final String SPAN_NAME = "read_only_analysis_model";

    private final ReadOnlyAnalysisServiceFactory serviceFactory;
    private final GenerationExecutionContextService executionContextService;
    private final GenerationPerformanceMonitorService performanceMonitorService;
    private final GenerationSynchronousModelCallSupervisor modelCallSupervisor;

    ManagedReadOnlyAnalysisModel(ReadOnlyAnalysisServiceFactory serviceFactory,
                                 GenerationExecutionContextService executionContextService,
                                 GenerationPerformanceMonitorService performanceMonitorService,
                                 GenerationSynchronousModelCallSupervisor modelCallSupervisor) {
        this.serviceFactory = Objects.requireNonNull(serviceFactory, "只读分析 AI 服务工厂不能为空");
        this.executionContextService = Objects.requireNonNull(
                executionContextService, "生成执行上下文服务不能为空");
        this.performanceMonitorService = Objects.requireNonNull(
                performanceMonitorService, "生成性能监控服务不能为空");
        this.modelCallSupervisor = Objects.requireNonNull(
                modelCallSupervisor, "同步模型调用监督器不能为空");
    }

    @Override
    public ReadOnlyAnalysisResult analyze(String taskId, ReadOnlyAnalysisRequest request) {
        GenerationExecutionContext context = executionContextService.getByTaskId(taskId)
                .orElseThrow(() -> new GenerationExecutionPolicyException(
                        "只读分析缺少活动执行上下文，taskId=" + taskId));
        GenerationPerformanceMonitorService.SpanTimer span = performanceMonitorService.startSpan(
                taskId, SPAN_NAME, GenerationSpanCategory.MODEL);
        try {
            context.assertCanContinue();
            context.consume(GenerationBudgetKind.ROOT_MODEL_ATTEMPT);
            Duration timeout = context.clampTimeout(context.limits().modelCallTimeout());
            ReadOnlyAnalysisAiService service = serviceFactory.create(
                    timeout,
                    () -> context.consume(GenerationBudgetKind.MODEL_TURN),
                    () -> context.consume(GenerationBudgetKind.PROVIDER_FAILOVER_ATTEMPT));
            ReadOnlyAnalysisResult result = modelCallSupervisor.execute(
                    context,
                    () -> service.analyze(
                            request.operationType().name(),
                            request.userPrompt(),
                            String.join("\n", request.allowedReferences()),
                            request.projectContext()));
            context.assertCanContinue();
            span.close("success", "operation=" + request.operationType().name());
            return result;
        } catch (RuntimeException | Error failure) {
            span.failed(failure.getClass().getSimpleName());
            throw failure;
        }
    }
}
