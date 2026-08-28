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
import java.util.Set;

/** 证明 READ_ONLY 执行前后项目工作区内容完全一致。 */
@Component
public class ReadOnlyWorkspaceImmutabilityBenchmarkRule implements GenerationBenchmarkValidationRule {

    private static final String RULE_ID = "read_only_workspace_immutability";

    private final GenerationBenchmarkWorkspaceInspector inspector;

    public ReadOnlyWorkspaceImmutabilityBenchmarkRule(GenerationBenchmarkWorkspaceInspector inspector) {
        this.inspector = inspector;
    }

    @Override
    public String id() {
        return RULE_ID;
    }

    @Override
    public GenerationBenchmarkQualityDimension dimension() {
        return GenerationBenchmarkQualityDimension.DIFF_SCOPE;
    }

    @Override
    public boolean supports(GenerationBenchmarkTask task) {
        return task != null && "READ_ONLY".equalsIgnoreCase(task.mode());
    }

    @Override
    public GenerationBenchmarkRuleResult evaluate(
            GenerationBenchmarkTask task,
            GenerationWorkspace workspace,
            GenerationBenchmarkWorkspaceSnapshot baseline
    ) {
        Set<String> changedPaths = baseline.changedPaths(
                inspector.capture(workspace.canonicalRootPath()));
        return new GenerationBenchmarkRuleResult(
                RULE_ID,
                dimension(),
                changedPaths.isEmpty(),
                changedPaths.isEmpty() ? List.of() : List.of("read_only_workspace_changed"),
                changedPaths.size()
        );
    }
}
