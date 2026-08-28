package com.rush.rushaicodemother.orchestration.benchmark.rule;

import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkDifficulty;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkFixtureKind;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkQualityDimension;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkRuleResult;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkTask;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkWorkspaceInspector;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkWorkspaceSnapshot;
import com.rush.rushaicodemother.orchestration.intent.IntentOperationType;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CrossTypeUpgradeBenchmarkRuleTest {

    @TempDir
    Path tempDir;

    @Test
    void crossTypeRuleMustCompareSourceAndPublishedLogicalWorkspaces() throws Exception {
        GenerationBenchmarkWorkspaceInspector inspector = new GenerationBenchmarkWorkspaceInspector();
        GenerationWorkspace source = workspace(
                tempDir.resolve("source"), CodeGenTypeEnum.VUE_PROJECT);
        Files.createDirectories(source.canonicalRootPath().resolve("src"));
        Files.writeString(source.canonicalRootPath().resolve("src/App.vue"), "Harbor Atlas");
        GenerationBenchmarkWorkspaceSnapshot baseline = inspector.captureBaseline(
                source, CodeGenTypeEnum.FULL_STACK_PROJECT);

        GenerationWorkspace published = workspace(
                tempDir.resolve("published"), CodeGenTypeEnum.FULL_STACK_PROJECT);
        Files.createDirectories(published.frontendRootPath().resolve("src"));
        Files.createDirectories(published.backendRootPath());
        Files.writeString(published.frontendRootPath().resolve("src/App.vue"), "Harbor Atlas");
        Files.writeString(published.backendRootPath().resolve("main.go"), "package main");

        GenerationBenchmarkTask task = crossTypeTask();
        CrossTypeUpgradeBenchmarkRule rule = new CrossTypeUpgradeBenchmarkRule(inspector);

        GenerationBenchmarkRuleResult result = rule.evaluate(task, published, baseline);

        assertTrue(rule.supports(task));
        assertTrue(result.passed());
        assertTrue(result.changedFileCount() > 0);
        assertFalse(new EditDiffScopeBenchmarkRule(inspector).supports(task));
    }

    private GenerationBenchmarkTask crossTypeTask() {
        return new GenerationBenchmarkTask(
                "upgrade_vue_fullstack",
                "HEAVY_EXPERT",
                "full_stack_project",
                "升级为全栈项目",
                "build",
                "cross_type_upgrade",
                GenerationBenchmarkDifficulty.HARD,
                List.of("project_migration"),
                List.of(
                        GenerationBenchmarkQualityDimension.STRUCTURAL,
                        GenerationBenchmarkQualityDimension.FUNCTIONAL,
                        GenerationBenchmarkQualityDimension.DIFF_SCOPE,
                        GenerationBenchmarkQualityDimension.SECURITY,
                        GenerationBenchmarkQualityDimension.RUNTIME,
                        GenerationBenchmarkQualityDimension.VISUAL),
                List.of(),
                List.of(),
                "HEAVY_EXPERT",
                List.of("CREATE", "LIGHT_EDIT", "AGENT_EDIT"),
                IntentOperationType.EDIT,
                GenerationBenchmarkFixtureKind.TEMPLATE_PROJECT,
                List.of(),
                "vue_project"
        );
    }

    private GenerationWorkspace workspace(Path root, CodeGenTypeEnum type) {
        Path normalized = root.toAbsolutePath().normalize();
        Path frontend = type == CodeGenTypeEnum.FULL_STACK_PROJECT
                ? normalized.resolve("frontend")
                : normalized;
        Path backend = type == CodeGenTypeEnum.FULL_STACK_PROJECT
                ? normalized.resolve("backend")
                : null;
        return new GenerationWorkspace(
                101L, type, normalized, normalized, true,
                frontend, backend, Set.of(), Set.of("vue", "go"));
    }
}
