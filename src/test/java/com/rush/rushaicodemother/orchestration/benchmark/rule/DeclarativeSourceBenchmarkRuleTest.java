package com.rush.rushaicodemother.orchestration.benchmark.rule;

import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkDeclarationValidator;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkDifficulty;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkFixtureFile;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkQualityDimension;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkRuleResult;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkSourceAssertion;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkSourceRoot;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkTask;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkWorkspaceInspector;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkWorkspaceSnapshot;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeclarativeSourceBenchmarkRuleTest {

    @TempDir
    Path root;

    private final GenerationBenchmarkWorkspaceInspector inspector =
            new GenerationBenchmarkWorkspaceInspector();
    private final DeclarativeSourceBenchmarkRule rule =
            new DeclarativeSourceBenchmarkRule(inspector);

    @Test
    void fixtureAndAllAnyNoneAssertionsMustBeApplied() {
        GenerationBenchmarkTask task = task("src/benchmark/probe.ts", "旧值");
        GenerationWorkspace workspace = workspace();
        rule.prepare(task, workspace);

        GenerationBenchmarkRuleResult before = rule.evaluate(
                task, workspace, new GenerationBenchmarkWorkspaceSnapshot(root, Map.of()));
        assertFalse(before.passed());

        inspector.writeUtf8(root, "src/benchmark/probe.ts", "export const value = '新值'; // 备用值\n");
        GenerationBenchmarkRuleResult after = rule.evaluate(
                task, workspace, new GenerationBenchmarkWorkspaceSnapshot(root, Map.of()));

        assertTrue(after.passed());
    }

    @Test
    void pathEscapingWorkspaceMustBeRejected() {
        GenerationBenchmarkTask task = task("../outside.ts", "旧值");

        assertThrows(IllegalArgumentException.class, () -> rule.prepare(task, workspace()));
    }

    @Test
    void oversizedSourceFileMustBeRejected() throws Exception {
        GenerationBenchmarkTask task = task("src/benchmark/probe.ts", "旧值");
        GenerationWorkspace workspace = workspace();
        rule.prepare(task, workspace);
        Files.writeString(
                root.resolve("src/benchmark/probe.ts"),
                "x".repeat(GenerationBenchmarkDeclarationValidator.MAX_SOURCE_FILE_CHARS + 1)
        );

        assertThrows(IllegalStateException.class, () -> rule.evaluate(
                task, workspace, new GenerationBenchmarkWorkspaceSnapshot(root, Map.of())));
    }

    private GenerationBenchmarkTask task(String path, String fixtureContent) {
        return new GenerationBenchmarkTask(
                "declared_source_test",
                "LIGHT_EDIT",
                "vue_project",
                "修改基准源码",
                "fast",
                "test",
                GenerationBenchmarkDifficulty.EASY,
                List.of("vue"),
                List.of(
                        GenerationBenchmarkQualityDimension.STRUCTURAL,
                        GenerationBenchmarkQualityDimension.FUNCTIONAL,
                        GenerationBenchmarkQualityDimension.DIFF_SCOPE,
                        GenerationBenchmarkQualityDimension.SECURITY
                ),
                List.of(new GenerationBenchmarkFixtureFile(
                        GenerationBenchmarkSourceRoot.FRONTEND,
                        path,
                        fixtureContent
                )),
                List.of(new GenerationBenchmarkSourceAssertion(
                        "probe",
                        GenerationBenchmarkSourceRoot.FRONTEND,
                        List.of(path),
                        List.of("新值"),
                        List.of("首选值", "备用值"),
                        List.of("旧值")
                ))
        );
    }

    private GenerationWorkspace workspace() {
        return new GenerationWorkspace(
                1L,
                CodeGenTypeEnum.VUE_PROJECT,
                root,
                root,
                true,
                root,
                null,
                Set.of(),
                Set.of("ts")
        );
    }
}
