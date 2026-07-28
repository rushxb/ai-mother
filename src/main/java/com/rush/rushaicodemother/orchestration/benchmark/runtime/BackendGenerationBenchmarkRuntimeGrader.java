package com.rush.rushaicodemother.orchestration.benchmark.runtime;

import com.rush.rushaicodemother.config.GenerationBenchmarkBackendProperties;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkQualityDimension;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkRuleResult;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkRuntimeContext;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkRuntimeGrader;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkTask;
import org.springframework.stereotype.Component;

import java.util.List;

/** 以真实 HTTP 健康契约评估生成的独立后端项目。 */
@Component
public class BackendGenerationBenchmarkRuntimeGrader implements GenerationBenchmarkRuntimeGrader {

    private static final String GRADER_ID = "backend_runtime";
    private static final String RULE_ID = "backend_health_runtime";

    private final GenerationBenchmarkBackendProperties properties;
    private final GenerationBenchmarkBackendRuntime backendRuntime;

    public BackendGenerationBenchmarkRuntimeGrader(
            GenerationBenchmarkBackendProperties properties,
            GenerationBenchmarkBackendRuntime backendRuntime
    ) {
        this.properties = properties;
        this.backendRuntime = backendRuntime;
    }

    @Override
    public String id() {
        return GRADER_ID;
    }

    @Override
    public List<GenerationBenchmarkQualityDimension> dimensions() {
        return List.of(GenerationBenchmarkQualityDimension.RUNTIME);
    }

    /**
 * 返回{@code supports}。
 *
 * @param task 任务
 * @return 满足条件时返回 {@code true}，否则返回 {@code false}
 */
    @Override
    public boolean supports(GenerationBenchmarkTask task) {
        return properties.isEnabled()
                && task != null
                && CodeGenTypeEnum.getEnumByValue(task.codeGenType())
                == CodeGenTypeEnum.BACKEND_PROJECT;
    }

    /**
 * 返回{@code evaluate}。
 *
 * @param context 执行上下文
 * @return 后端生成基准测试运行时{@code Grader}集合
 */
    @Override
    public List<GenerationBenchmarkRuleResult> evaluate(GenerationBenchmarkRuntimeContext context) {
        try (BackendRuntimeHandle handle = backendRuntime.start(
                context.workspace().backendRootPath()
        )) {
            BackendRuntimeObservation observation = handle.observation();
            return List.of(new GenerationBenchmarkRuleResult(
                    RULE_ID,
                    GenerationBenchmarkQualityDimension.RUNTIME,
                    observation.passedValidation(),
                    observation.violations(),
                    0
            ));
        }
    }
}
