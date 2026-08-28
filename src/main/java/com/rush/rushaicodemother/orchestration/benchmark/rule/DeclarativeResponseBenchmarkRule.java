package com.rush.rushaicodemother.orchestration.benchmark.rule;

import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkQualityDimension;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkResponseAssertion;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkResponseRule;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkRuleResult;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkTask;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** 执行数据集声明的最终响应字符串断言。 */
@Component
public class DeclarativeResponseBenchmarkRule implements GenerationBenchmarkResponseRule {

    private static final String RULE_ID = "declared_response_behavior";

    @Override
    public String id() {
        return RULE_ID;
    }

    @Override
    public GenerationBenchmarkQualityDimension dimension() {
        return GenerationBenchmarkQualityDimension.FUNCTIONAL;
    }

    @Override
    public boolean supports(GenerationBenchmarkTask task) {
        return task != null && !task.responseAssertions().isEmpty();
    }

    @Override
    public int order() {
        return 20;
    }

    @Override
    public GenerationBenchmarkRuleResult evaluate(GenerationBenchmarkTask task, String responseText) {
        String response = responseText == null ? "" : responseText;
        List<String> violations = new ArrayList<>();
        for (GenerationBenchmarkResponseAssertion assertion : task.responseAssertions()) {
            if (!assertion.allOf().stream().allMatch(response::contains)) {
                violations.add(assertion.id() + "_all_of_missing");
            }
            if (!assertion.anyOf().isEmpty()
                    && assertion.anyOf().stream().noneMatch(response::contains)) {
                violations.add(assertion.id() + "_any_of_missing");
            }
            if (assertion.noneOf().stream().anyMatch(response::contains)) {
                violations.add(assertion.id() + "_forbidden_present");
            }
        }
        return new GenerationBenchmarkRuleResult(
                RULE_ID,
                dimension(),
                violations.isEmpty(),
                violations,
                0
        );
    }
}
