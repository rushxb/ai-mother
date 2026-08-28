package com.rush.rushaicodemother.orchestration.benchmark.rule;

import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkFixtureKind;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkQualityDimension;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkResponseRule;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkRuleResult;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkTask;
import com.rush.rushaicodemother.orchestration.intent.IntentOperationType;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** 校验只读报告包含结论和未改动说明，并遵守仓库证据合同。 */
@Component
public class ReadOnlyResponseBenchmarkRule implements GenerationBenchmarkResponseRule {

    private static final String RULE_ID = "read_only_response_contract";
    private static final String CONCLUSION_SECTION = "## 分析结论";
    private static final String REFERENCE_SECTION = "## 文件依据";
    private static final String NO_CHANGE_SECTION = "## 未改动说明";

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
        return task != null && "READ_ONLY".equalsIgnoreCase(task.mode());
    }

    @Override
    public GenerationBenchmarkRuleResult evaluate(GenerationBenchmarkTask task, String responseText) {
        String response = responseText == null ? "" : responseText.trim();
        List<String> violations = new ArrayList<>();
        if (!response.contains(CONCLUSION_SECTION)) {
            violations.add("read_only_conclusion_missing");
        }
        if (!response.contains(NO_CHANGE_SECTION)) {
            violations.add("read_only_no_change_justification_missing");
        }
        boolean hasReferences = response.contains(REFERENCE_SECTION);
        if (task.operation() == IntentOperationType.PLAN
                && task.fixtureKind() == GenerationBenchmarkFixtureKind.EMPTY_PROJECT
                && hasReferences) {
            violations.add("empty_plan_contains_repository_reference");
        }
        if ((task.operation() == IntentOperationType.EXPLAIN
                || task.operation() == IntentOperationType.AUDIT)
                && task.fixtureKind() == GenerationBenchmarkFixtureKind.TEMPLATE_PROJECT
                && !hasReferences) {
            violations.add("repository_evidence_missing");
        }
        return new GenerationBenchmarkRuleResult(
                RULE_ID, dimension(), violations.isEmpty(), violations, 0);
    }
}
