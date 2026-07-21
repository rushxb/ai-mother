package com.rush.rushaicodemother.orchestration.benchmark.rule;

import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkQualityDimension;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkRuleResult;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkTask;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkValidationRule;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkWorkspaceInspector;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkWorkspaceSnapshot;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import org.springframework.stereotype.Component;

import java.util.Locale;

/** Seeds a build-valid undefined dereference rendered by the root app. */
@Component
public class RuntimeUndefinedBenchmarkRule implements GenerationBenchmarkValidationRule {

    private static final String TASK_ID = "edit_runtime_error";
    private static final String RULE_ID = "runtime_undefined_repair";
    private static final String COMPONENT = "BenchmarkRuntimeProbe";

    private final GenerationBenchmarkWorkspaceInspector inspector;

    public RuntimeUndefinedBenchmarkRule(GenerationBenchmarkWorkspaceInspector inspector) {
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
                  <p data-benchmark-runtime-user>{{ benchmarkUser.name }}</p>
                </template>

                <script setup lang="ts">
                const benchmarkUser: any = undefined
                </script>
                """);
    }

    @Override
    public GenerationBenchmarkRuleResult evaluate(
            GenerationBenchmarkTask task,
            GenerationWorkspace workspace,
            GenerationBenchmarkWorkspaceSnapshot baseline
    ) {
        String path = "src/benchmark/" + COMPONENT + ".vue";
        if (!inspector.exists(workspace.frontendRootPath(), path)) {
            String app = inspector.readUtf8(workspace.frontendRootPath(), "src/App.vue");
            boolean removedCleanly = !app.contains(COMPONENT);
            return removedCleanly
                    ? GenerationBenchmarkRuleResult.passed(RULE_ID, dimension())
                    : GenerationBenchmarkRuleResult.failed(
                            RULE_ID, dimension(), "deleted_runtime_probe_still_referenced");
        }
        String normalized = inspector.readUtf8(workspace.frontendRootPath(), path)
                .replaceAll("\\s+", "")
                .toLowerCase(Locale.ROOT);
        boolean unsafeDereference = normalized.contains("benchmarkuser.name");
        boolean optionalDereference = normalized.contains("benchmarkuser?.name");
        boolean assignedUndefined = normalized.contains("benchmarkuser:any=undefined")
                || normalized.contains("benchmarkuser=undefined");
        boolean passed = optionalDereference || !unsafeDereference || !assignedUndefined;
        return passed
                ? GenerationBenchmarkRuleResult.passed(RULE_ID, dimension())
                : GenerationBenchmarkRuleResult.failed(
                        RULE_ID, dimension(), "undefined_dereference_still_present");
    }
}
