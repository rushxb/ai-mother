package com.rush.rushaicodemother.orchestration.benchmark.rule;

import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkQualityDimension;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkRuleResult;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkTask;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkValidationRule;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkWorkspaceInspector;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkWorkspaceSnapshot;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/** Seeds a visible button style fixture and verifies the exact requested design tokens. */
@Component
public class StyleEditBenchmarkRule implements GenerationBenchmarkValidationRule {

    private static final String TASK_ID = "edit_style";
    private static final String RULE_ID = "button_style";
    private static final String COMPONENT = "BenchmarkStyleProbe";
    private static final Pattern TARGET_GAP = Pattern.compile("gap\\s*:\\s*12px", Pattern.CASE_INSENSITIVE);

    private final GenerationBenchmarkWorkspaceInspector inspector;

    public StyleEditBenchmarkRule(GenerationBenchmarkWorkspaceInspector inspector) {
        this.inspector = inspector;
    }

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
        return task != null && TASK_ID.equals(task.id());
    }

    @Override
    public void prepare(GenerationBenchmarkTask task, GenerationWorkspace workspace) {
        VueBenchmarkRuleSupport.mountProbe(inspector, workspace, COMPONENT, """
                <template>
                  <div class="benchmark-actions">
                    <button class="benchmark-primary" type="button">立即开始</button>
                    <button type="button">了解更多</button>
                  </div>
                </template>

                <style scoped>
                .benchmark-actions {
                  display: flex;
                  gap: 4px;
                }

                .benchmark-primary {
                  background: #ef4444;
                  color: white;
                }
                </style>
                """);
    }

    @Override
    public GenerationBenchmarkRuleResult evaluate(
            GenerationBenchmarkTask task,
            GenerationWorkspace workspace,
            GenerationBenchmarkWorkspaceSnapshot baseline
    ) {
        String content = inspector.readUtf8(
                workspace.frontendRootPath(), "src/benchmark/" + COMPONENT + ".vue");
        boolean passed = content.toLowerCase().contains("#2563eb")
                && TARGET_GAP.matcher(content).find()
                && !content.toLowerCase().contains("#ef4444");
        return passed
                ? GenerationBenchmarkRuleResult.passed(RULE_ID, dimension())
                : new GenerationBenchmarkRuleResult(
                        RULE_ID, dimension(), false, List.of("requested_style_tokens_missing"), 0);
    }
}
