package com.rush.rushaicodemother.orchestration.benchmark.runtime;

import com.rush.rushaicodemother.config.GenerationBenchmarkBackendProperties;
import com.rush.rushaicodemother.config.GenerationBenchmarkBrowserProperties;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkQualityDimension;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkRuleResult;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkRuntimeContext;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkRuntimeGrader;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkTask;
import com.rush.rushaicodemother.orchestration.verification.runtime.FullStackRuntimeValidationResult;
import com.rush.rushaicodemother.orchestration.verification.runtime.GeneratedFullStackRuntimeVerifier;
import com.rush.rushaicodemother.service.browser.BrowserRuntimeValidationPolicy;
import com.rush.rushaicodemother.service.browser.BrowserRuntimeValidationResult;
import com.rush.rushaicodemother.service.devserver.DevServerValidationRequest;
import com.rush.rushaicodemother.service.devserver.DevServerValidationResult;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 在同一评分窗口内联合验证全栈项目的后端、前端运行时与视觉结果。 */
@Component
public class FullStackGenerationBenchmarkRuntimeGrader implements GenerationBenchmarkRuntimeGrader {

    private static final String GRADER_ID = "fullstack_runtime";
    private static final String BACKEND_RULE_ID = "fullstack_backend_runtime";
    private static final String BROWSER_RUNTIME_RULE_ID = "browser_runtime";
    private static final String BROWSER_VISUAL_RULE_ID = "browser_visual";
    private static final String VISUAL_SKIPPED_RULE_ID = "fullstack_visual_prerequisite";
    private static final List<GenerationBenchmarkQualityDimension> DIMENSIONS = List.of(
            GenerationBenchmarkQualityDimension.RUNTIME,
            GenerationBenchmarkQualityDimension.VISUAL
    );

    private final GenerationBenchmarkBackendProperties backendProperties;
    private final GenerationBenchmarkBrowserProperties browserProperties;
    private final GeneratedFullStackRuntimeVerifier fullStackRuntimeVerifier;

    public FullStackGenerationBenchmarkRuntimeGrader(
            GenerationBenchmarkBackendProperties backendProperties,
            GenerationBenchmarkBrowserProperties browserProperties,
            GeneratedFullStackRuntimeVerifier fullStackRuntimeVerifier
    ) {
        this.backendProperties = backendProperties;
        this.browserProperties = browserProperties;
        this.fullStackRuntimeVerifier = fullStackRuntimeVerifier;
    }

    @Override
    public String id() {
        return GRADER_ID;
    }

    @Override
    public List<GenerationBenchmarkQualityDimension> dimensions() {
        return DIMENSIONS;
    }

    @Override
    public boolean supports(GenerationBenchmarkTask task) {
        return backendProperties.isEnabled()
                && browserProperties.isEnabled()
                && task != null
                && !"READ_ONLY".equalsIgnoreCase(task.mode())
                && CodeGenTypeEnum.getEnumByValue(task.codeGenType())
                == CodeGenTypeEnum.FULL_STACK_PROJECT;
    }

    @Override
    public List<GenerationBenchmarkRuleResult> evaluate(GenerationBenchmarkRuntimeContext context) {
        FullStackRuntimeValidationResult validation = fullStackRuntimeVerifier.verify(
                context.workspace().backendRootPath(),
                DevServerValidationRequest.of(
                        context.task().id(),
                        context.workspace().appId(),
                        context.userId(),
                        CodeGenTypeEnum.FULL_STACK_PROJECT
                ),
                BrowserRuntimeValidationPolicy.benchmark(
                        browserProperties.getSettleDelay())
        );
        List<GenerationBenchmarkRuleResult> results = new ArrayList<>();
        results.add(result(
                BACKEND_RULE_ID,
                GenerationBenchmarkQualityDimension.RUNTIME,
                validation.backend().violations()
        ));
        DevServerValidationResult frontend = validation.frontend();
        if (frontend == null || frontend.browserValidation() == null) {
            results.add(result(
                    VISUAL_SKIPPED_RULE_ID,
                    GenerationBenchmarkQualityDimension.VISUAL,
                    List.of("fullstack_visual_skipped_backend_unhealthy")
            ));
            return List.copyOf(results);
        }
        BrowserRuntimeValidationResult browser = frontend.browserValidation();
        List<String> runtimeViolations = new ArrayList<>(browser.runtimeViolations());
        if (!frontend.isPassed()
                && frontend.failureKind()
                != DevServerValidationResult.ValidationFailureKind.BROWSER_RUNTIME_ERROR) {
            runtimeViolations.add("dev_server_"
                    + frontend.failureKind().name().toLowerCase(Locale.ROOT));
        }
        results.add(result(
                BROWSER_RUNTIME_RULE_ID,
                GenerationBenchmarkQualityDimension.RUNTIME,
                runtimeViolations
        ));
        results.add(result(
                BROWSER_VISUAL_RULE_ID,
                GenerationBenchmarkQualityDimension.VISUAL,
                browser.visualViolations()
        ));
        return List.copyOf(results);
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
