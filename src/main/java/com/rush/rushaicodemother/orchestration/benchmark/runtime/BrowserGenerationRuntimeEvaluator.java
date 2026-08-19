package com.rush.rushaicodemother.orchestration.benchmark.runtime;

import com.rush.rushaicodemother.config.GenerationBenchmarkBrowserProperties;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkQualityDimension;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkRuleResult;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkRuntimeContext;
import com.rush.rushaicodemother.service.browser.BrowserRuntimeValidationPolicy;
import com.rush.rushaicodemother.service.browser.BrowserRuntimeValidationResult;
import com.rush.rushaicodemother.service.devserver.DevServerValidationRequest;
import com.rush.rushaicodemother.service.devserver.DevServerValidationResult;
import com.rush.rushaicodemother.service.devserver.DevServerValidationService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 将生产 Dev Server 验证事实映射为 Benchmark 的运行时与视觉维度。
 *
 * <p>该类不再拥有进程生命周期或浏览器探测实现；这些副作用统一由
 * {@link DevServerValidationService} 执行，避免 Benchmark 与生产形成两套 verifier。</p>
 */
@Component
public class BrowserGenerationRuntimeEvaluator {

    private static final String RUNTIME_RULE_ID = "browser_runtime";
    private static final String VISUAL_RULE_ID = "browser_visual";

    private final GenerationBenchmarkBrowserProperties properties;
    private final DevServerValidationService devServerValidationService;

    public BrowserGenerationRuntimeEvaluator(
            GenerationBenchmarkBrowserProperties properties,
            DevServerValidationService devServerValidationService
    ) {
        this.properties = properties;
        this.devServerValidationService = devServerValidationService;
    }

    public List<GenerationBenchmarkRuleResult> evaluate(
            GenerationBenchmarkRuntimeContext context,
            CodeGenTypeEnum codeGenType
    ) {
        DevServerValidationResult validation = devServerValidationService.validate(
                DevServerValidationRequest.of(
                                context.task().id(),
                                context.workspace().appId(),
                                context.userId(),
                                codeGenType)
                        .withBrowserValidation(BrowserRuntimeValidationPolicy.benchmark(
                                properties.getSettleDelay()))
        );
        BrowserRuntimeValidationResult browser = validation == null
                ? null
                : validation.browserValidation();
        return List.of(
                gradeRuntime(validation, browser),
                result(
                        VISUAL_RULE_ID,
                        GenerationBenchmarkQualityDimension.VISUAL,
                        browser == null
                                ? List.of("browser_visual_evidence_missing")
                                : browser.visualViolations()
                )
        );
    }

    private GenerationBenchmarkRuleResult gradeRuntime(
            DevServerValidationResult validation,
            BrowserRuntimeValidationResult browser
    ) {
        List<String> violations = new ArrayList<>();
        if (browser != null) {
            violations.addAll(browser.runtimeViolations());
        }
        if (validation == null) {
            violations.add("dev_server_validation_result_missing");
        } else if (!validation.isPassed()
                && validation.failureKind()
                != DevServerValidationResult.ValidationFailureKind.BROWSER_RUNTIME_ERROR) {
            violations.add(devServerViolation(validation.failureKind()));
        } else if (browser == null) {
            violations.add("browser_runtime_evidence_missing");
        }
        return result(RUNTIME_RULE_ID, GenerationBenchmarkQualityDimension.RUNTIME, violations);
    }

    private String devServerViolation(
            DevServerValidationResult.ValidationFailureKind failureKind
    ) {
        if (failureKind == DevServerValidationResult.ValidationFailureKind.RUNTIME_ERROR) {
            return "dev_server_critical_error";
        }
        return "dev_server_" + failureKind.name().toLowerCase(Locale.ROOT);
    }

    private GenerationBenchmarkRuleResult result(
            String ruleId,
            GenerationBenchmarkQualityDimension dimension,
            List<String> violations
    ) {
        return new GenerationBenchmarkRuleResult(
                ruleId,
                dimension,
                violations.isEmpty(),
                violations,
                0
        );
    }

}
