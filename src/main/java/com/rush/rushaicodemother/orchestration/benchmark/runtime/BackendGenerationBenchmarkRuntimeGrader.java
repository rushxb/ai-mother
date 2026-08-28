package com.rush.rushaicodemother.orchestration.benchmark.runtime;

import com.rush.rushaicodemother.config.GenerationBenchmarkBackendProperties;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkQualityDimension;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkRuleResult;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkRuntimeContext;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkRuntimeGrader;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkTask;
import com.rush.rushaicodemother.orchestration.verification.runtime.GeneratedBackendRuntime;
import com.rush.rushaicodemother.orchestration.verification.runtime.GeneratedBackendRuntimeHandle;
import com.rush.rushaicodemother.orchestration.verification.runtime.GeneratedBackendRuntimeObservation;
import org.springframework.stereotype.Component;

import java.util.List;

/** 以真实 HTTP 健康契约评估生成的独立后端项目。 */
@Component
public class BackendGenerationBenchmarkRuntimeGrader implements GenerationBenchmarkRuntimeGrader {

    private static final String GRADER_ID = "backend_runtime";
    private static final String RULE_ID = "backend_health_runtime";

    private final GenerationBenchmarkBackendProperties properties;
    private final GeneratedBackendRuntime backendRuntime;

    public BackendGenerationBenchmarkRuntimeGrader(
            GenerationBenchmarkBackendProperties properties,
            GeneratedBackendRuntime backendRuntime
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

    @Override
    public boolean supports(GenerationBenchmarkTask task) {
        return properties.isEnabled()
                && task != null
                && !"READ_ONLY".equalsIgnoreCase(task.mode())
                && CodeGenTypeEnum.getEnumByValue(task.codeGenType())
                == CodeGenTypeEnum.BACKEND_PROJECT;
    }

    @Override
    public List<GenerationBenchmarkRuleResult> evaluate(GenerationBenchmarkRuntimeContext context) {
        try (GeneratedBackendRuntimeHandle handle = backendRuntime.start(
                context.workspace().backendRootPath()
        )) {
            GeneratedBackendRuntimeObservation observation = handle.observation();
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
