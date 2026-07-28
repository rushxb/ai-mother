package com.rush.rushaicodemother.orchestration.benchmark.runtime;

import com.rush.rushaicodemother.config.DevServerRuntimeProperties;
import com.rush.rushaicodemother.config.GenerationBenchmarkBackendProperties;
import com.rush.rushaicodemother.config.GenerationBenchmarkBrowserProperties;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkQualityDimension;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkRuleResult;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkRuntimeContext;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkRuntimeGrader;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkTask;
import com.rush.rushaicodemother.service.devserver.DevServerStartOptions;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 在同一评分窗口内联合验证全栈项目的后端、前端运行时与视觉结果。 */
@Component
public class FullStackGenerationBenchmarkRuntimeGrader implements GenerationBenchmarkRuntimeGrader {

    private static final String GRADER_ID = "fullstack_runtime";
    private static final String BACKEND_RULE_ID = "fullstack_backend_runtime";
    private static final String BACKEND_LIVENESS_RULE_ID = "fullstack_backend_liveness";
    private static final String VISUAL_SKIPPED_RULE_ID = "fullstack_visual_prerequisite";
    private static final List<GenerationBenchmarkQualityDimension> DIMENSIONS = List.of(
            GenerationBenchmarkQualityDimension.RUNTIME,
            GenerationBenchmarkQualityDimension.VISUAL
    );

    private final GenerationBenchmarkBackendProperties backendProperties;
    private final GenerationBenchmarkBrowserProperties browserProperties;
    private final DevServerRuntimeProperties devServerProperties;
    private final GenerationBenchmarkBackendRuntime backendRuntime;
    private final BrowserGenerationRuntimeEvaluator browserEvaluator;

    public FullStackGenerationBenchmarkRuntimeGrader(
            GenerationBenchmarkBackendProperties backendProperties,
            GenerationBenchmarkBrowserProperties browserProperties,
            DevServerRuntimeProperties devServerProperties,
            GenerationBenchmarkBackendRuntime backendRuntime,
            BrowserGenerationRuntimeEvaluator browserEvaluator
    ) {
        this.backendProperties = backendProperties;
        this.browserProperties = browserProperties;
        this.devServerProperties = devServerProperties;
        this.backendRuntime = backendRuntime;
        this.browserEvaluator = browserEvaluator;
    }

    @Override
    public String id() {
        return GRADER_ID;
    }

    @Override
    public List<GenerationBenchmarkQualityDimension> dimensions() {
        return DIMENSIONS;
    }

    /**
 * 返回{@code supports}。
 *
 * @param task 任务
 * @return 满足条件时返回 {@code true}，否则返回 {@code false}
 */
    @Override
    public boolean supports(GenerationBenchmarkTask task) {
        return backendProperties.isEnabled()
                && browserProperties.isEnabled()
                && task != null
                && CodeGenTypeEnum.getEnumByValue(task.codeGenType())
                == CodeGenTypeEnum.FULL_STACK_PROJECT;
    }

    /**
 * 返回{@code evaluate}。
 *
 * @param context 执行上下文
 * @return 全栈生成基准测试运行时{@code Grader}集合
 */
    @Override
    public List<GenerationBenchmarkRuleResult> evaluate(GenerationBenchmarkRuntimeContext context) {
        try (BackendRuntimeHandle backend = backendRuntime.start(
                context.workspace().backendRootPath()
        )) {
            if (!backend.healthy()) {
                return List.of(
                        result(
                                BACKEND_RULE_ID,
                                GenerationBenchmarkQualityDimension.RUNTIME,
                                backend.observation().violations()
                        ),
                        result(
                                VISUAL_SKIPPED_RULE_ID,
                                GenerationBenchmarkQualityDimension.VISUAL,
                                List.of("fullstack_visual_skipped_backend_unhealthy")
                        )
                );
            }

            List<GenerationBenchmarkRuleResult> results = new ArrayList<>();
            results.add(result(
                    BACKEND_RULE_ID,
                    GenerationBenchmarkQualityDimension.RUNTIME,
                    List.of()
            ));
            DevServerStartOptions startOptions = new DevServerStartOptions(
                    context.task().id(),
                    devServerProperties.getStartupTimeout(),
                    () -> Thread.currentThread().isInterrupted(),
                    null,
                    Map.of(
                            "VITE_API_BASE_URL",
                            "http://127.0.0.1:" + backend.port() + "/api"
                    )
            );
            results.addAll(browserEvaluator.evaluate(
                    context,
                    CodeGenTypeEnum.FULL_STACK_PROJECT,
                    startOptions
            ));
            if (!backend.processAlive()) {
                results.add(result(
                        BACKEND_LIVENESS_RULE_ID,
                        GenerationBenchmarkQualityDimension.RUNTIME,
                        List.of("backend_process_exited_during_browser")
                ));
            }
            return List.copyOf(results);
        }
    }

    private GenerationBenchmarkRuleResult result(
            String ruleId,
            GenerationBenchmarkQualityDimension dimension,
            List<String> violations
    ) {
        return new GenerationBenchmarkRuleResult(
                ruleId,
                dimension,
                violations == null || violations.isEmpty(),
                violations,
                0
        );
    }
}
