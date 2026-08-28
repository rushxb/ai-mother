package com.rush.rushaicodemother.orchestration.benchmark;

import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.monitor.GenerationBenchmarkGraderMetricsCollector;
import com.rush.rushaicodemother.orchestration.benchmark.rule.ReadOnlyResponseBenchmarkRule;
import com.rush.rushaicodemother.orchestration.benchmark.rule.ReadOnlyWorkspaceImmutabilityBenchmarkRule;
import com.rush.rushaicodemother.orchestration.intent.IntentOperationType;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReadOnlyBenchmarkRulesTest {

    @TempDir
    Path temporaryDirectory;

    private final GenerationBenchmarkWorkspaceInspector inspector =
            new GenerationBenchmarkWorkspaceInspector();

    @Test
    void repositoryExplainMustRequireGroundedResponseAndPreserveWorkspace() {
        GenerationWorkspace workspace = workspace("explain");
        GenerationBenchmarkValidationEngine engine = engine();
        GenerationBenchmarkValidationPlan plan = engine.prepare(
                readOnlyTask(IntentOperationType.EXPLAIN, GenerationBenchmarkFixtureKind.TEMPLATE_PROJECT),
                workspace);

        GenerationBenchmarkQualityEvidence valid = engine.evaluate(plan, """
                ## 分析结论

                项目入口由 App.vue 负责。

                ## 文件依据

                - `src/App.vue`：应用入口

                ## 未改动说明

                本次只读分析未修改工作区。
                """);

        assertTrue(valid.passed(GenerationBenchmarkQualityDimension.FUNCTIONAL));
        assertTrue(valid.passed(GenerationBenchmarkQualityDimension.DIFF_SCOPE));

        inspector.writeUtf8(workspace.canonicalRootPath(), "src/App.vue", "changed");
        GenerationBenchmarkQualityEvidence changed = engine.evaluate(plan, """
                ## 分析结论

                已完成分析。

                ## 文件依据

                - `src/App.vue`：应用入口

                ## 未改动说明

                未修改工作区。
                """);

        assertFalse(changed.passed(GenerationBenchmarkQualityDimension.DIFF_SCOPE));
        assertTrue(changed.violations().stream()
                .anyMatch(value -> value.contains("read_only_workspace_changed")));
    }

    @Test
    void emptyPlanMustRejectFabricatedRepositoryReferences() {
        GenerationBenchmarkResponseRule rule = new ReadOnlyResponseBenchmarkRule();
        GenerationBenchmarkTask task = readOnlyTask(
                IntentOperationType.PLAN, GenerationBenchmarkFixtureKind.EMPTY_PROJECT);

        GenerationBenchmarkRuleResult valid = rule.evaluate(task, """
                ## 分析结论

                建议按领域拆分模块。

                ## 未改动说明

                当前为空项目，本次只输出计划。
                """);
        GenerationBenchmarkRuleResult fabricated = rule.evaluate(task, """
                ## 分析结论

                建议按领域拆分模块。

                ## 文件依据

                - `src/App.vue`：入口

                ## 未改动说明

                当前为空项目。
                """);

        assertTrue(valid.passed());
        assertFalse(fabricated.passed());
        assertTrue(fabricated.violations().contains("empty_plan_contains_repository_reference"));
    }

    private GenerationBenchmarkValidationEngine engine() {
        return new GenerationBenchmarkValidationEngine(
                List.of(new ReadOnlyWorkspaceImmutabilityBenchmarkRule(inspector)),
                List.of(new ReadOnlyResponseBenchmarkRule()),
                List.of(),
                inspector,
                GenerationBenchmarkGraderMetricsCollector.noOp()
        );
    }

    private GenerationBenchmarkTask readOnlyTask(
            IntentOperationType operation,
            GenerationBenchmarkFixtureKind fixtureKind
    ) {
        return new GenerationBenchmarkTask(
                "readonly_contract",
                "READ_ONLY",
                "vue_project",
                "只读分析",
                "fast",
                "readonly_contract",
                GenerationBenchmarkDifficulty.MEDIUM,
                List.of("read_only"),
                List.of(
                        GenerationBenchmarkQualityDimension.FUNCTIONAL,
                        GenerationBenchmarkQualityDimension.DIFF_SCOPE,
                        GenerationBenchmarkQualityDimension.SECURITY),
                List.of(),
                List.of(),
                "READ_ONLY",
                List.of(),
                operation,
                fixtureKind
        );
    }

    private GenerationWorkspace workspace(String name) {
        Path root = temporaryDirectory.resolve(name).toAbsolutePath().normalize();
        inspector.writeUtf8(root, "src/App.vue", "<template><main>demo</main></template>");
        return new GenerationWorkspace(
                101L,
                CodeGenTypeEnum.VUE_PROJECT,
                root,
                root,
                true,
                root,
                null,
                Set.of(),
                Set.of("vue")
        );
    }
}
