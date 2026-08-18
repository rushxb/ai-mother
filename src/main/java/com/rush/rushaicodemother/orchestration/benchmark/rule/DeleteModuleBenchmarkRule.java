package com.rush.rushaicodemother.orchestration.benchmark.rule;

import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkQualityDimension;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkRuleResult;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkTask;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkValidationRule;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkWorkspaceInspector;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkWorkspaceSnapshot;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** 播种明确未使用的统计模块并验证删除和引用清理。 */
@Component
public class DeleteModuleBenchmarkRule implements GenerationBenchmarkValidationRule {

    private static final String TASK_ID = "edit_delete_module";
    private static final String RULE_ID = "delete_statistics_module";
    private static final String COMPONENT = "LegacyStatistics";

    private final GenerationBenchmarkWorkspaceInspector inspector;

    public DeleteModuleBenchmarkRule(GenerationBenchmarkWorkspaceInspector inspector) {
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

    /**
 * 准备后续流程所需的删除模块基准测试规则。
 *
 * @param task 任务
 * @param workspace 工作区
 */
    @Override
    public void prepare(GenerationBenchmarkTask task, GenerationWorkspace workspace) {
        VueBenchmarkRuleSupport.mountProbe(inspector, workspace, COMPONENT, """
                <template>
                  <aside data-legacy-statistics>
                    <strong>旧统计模块</strong>
                    <span>42</span>
                  </aside>
                </template>
                """);
    }

    @Override
    public GenerationBenchmarkRuleResult evaluate(
            GenerationBenchmarkTask task,
            GenerationWorkspace workspace,
            GenerationBenchmarkWorkspaceSnapshot baseline
    ) {
        List<String> violations = new ArrayList<>();
        if (inspector.exists(
                workspace.frontendRootPath(), "src/benchmark/" + COMPONENT + ".vue")) {
            violations.add("statistics_module_not_deleted");
        }
        String app = inspector.readUtf8(workspace.frontendRootPath(), "src/App.vue");
        if (app.contains(COMPONENT)) {
            violations.add("statistics_module_reference_not_removed");
        }
        return new GenerationBenchmarkRuleResult(
                RULE_ID, dimension(), violations.isEmpty(), violations, 0);
    }
}
