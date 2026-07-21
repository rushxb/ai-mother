package com.rush.rushaicodemother.orchestration.benchmark;

import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.monitor.GenerationBenchmarkGraderMetricsCollector;
import com.rush.rushaicodemother.orchestration.benchmark.rule.BrandCopyBenchmarkRule;
import com.rush.rushaicodemother.orchestration.benchmark.rule.DeleteModuleBenchmarkRule;
import com.rush.rushaicodemother.orchestration.benchmark.rule.EditDiffScopeBenchmarkRule;
import com.rush.rushaicodemother.orchestration.benchmark.rule.MissingImportBenchmarkRule;
import com.rush.rushaicodemother.orchestration.benchmark.rule.RuntimeUndefinedBenchmarkRule;
import com.rush.rushaicodemother.orchestration.benchmark.rule.SearchPaginationBenchmarkRule;
import com.rush.rushaicodemother.orchestration.benchmark.rule.StyleEditBenchmarkRule;
import com.rush.rushaicodemother.orchestration.benchmark.rule.WorkspaceStructuralBenchmarkRule;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerationBenchmarkValidationEngineTest {

    @TempDir
    Path temporaryDirectory;

    private final GenerationBenchmarkWorkspaceInspector inspector =
            new GenerationBenchmarkWorkspaceInspector();

    @Test
    void brandFixtureMustRequireExactCopyAndKeepEditInScope() {
        GenerationWorkspace workspace = vueWorkspace("brand");
        GenerationBenchmarkValidationEngine engine = engine(new BrandCopyBenchmarkRule(inspector));
        GenerationBenchmarkTask task = task("edit_copy", "LIGHT_EDIT");

        GenerationBenchmarkValidationPlan plan = engine.prepare(task, workspace);
        assertTrue(inspector.readUtf8(workspace.frontendRootPath(), "src/data/siteData.ts")
                .contains("基准旧品牌"));
        inspector.writeUtf8(workspace.frontendRootPath(), "src/data/siteData.ts", """
                export const hero = { title: '星河工作室' }
                """);

        GenerationBenchmarkQualityEvidence evidence = engine.evaluate(plan);

        assertTrue(evidence.passed(GenerationBenchmarkQualityDimension.STRUCTURAL));
        assertTrue(evidence.passed(GenerationBenchmarkQualityDimension.FUNCTIONAL));
        assertTrue(evidence.passed(GenerationBenchmarkQualityDimension.DIFF_SCOPE));
        assertTrue(evidence.changedFileCount() > 0);
    }

    @Test
    void diffScopeMustRejectDependencyManifestChanges() {
        GenerationWorkspace workspace = vueWorkspace("protected-change");
        GenerationBenchmarkValidationEngine engine = engine(new BrandCopyBenchmarkRule(inspector));
        GenerationBenchmarkValidationPlan plan = engine.prepare(task("edit_copy", "LIGHT_EDIT"), workspace);
        inspector.writeUtf8(workspace.frontendRootPath(), "src/data/siteData.ts", """
                export const hero = { title: '星河工作室' }
                """);
        inspector.writeUtf8(workspace.frontendRootPath(), "package.json", "{\"dependencies\":{\"x\":\"1\"}}");

        GenerationBenchmarkQualityEvidence evidence = engine.evaluate(plan);

        assertFalse(evidence.passed(GenerationBenchmarkQualityDimension.DIFF_SCOPE));
        assertTrue(evidence.violations().stream()
                .anyMatch(value -> value.contains("protected_dependency_file_changed")));
    }

    @Test
    void missingImportFixtureMustBeRealAndAcceptCreatedModuleRepair() {
        GenerationWorkspace workspace = vueWorkspace("missing-import");
        GenerationBenchmarkValidationEngine engine = engine(new MissingImportBenchmarkRule(inspector));
        GenerationBenchmarkValidationPlan plan = engine.prepare(
                task("edit_build_error", "AGENT_EDIT"), workspace);

        assertTrue(inspector.readUtf8(
                workspace.frontendRootPath(), "src/benchmark/benchmarkBrokenImport.ts")
                .contains("./benchmarkMissingModule"));
        inspector.writeUtf8(
                workspace.frontendRootPath(),
                "src/benchmark/benchmarkMissingModule.js",
                "export const benchmarkValue = 'fixed'\n"
        );

        assertTrue(engine.evaluate(plan).passed(GenerationBenchmarkQualityDimension.FUNCTIONAL));
    }

    @Test
    void runtimeFixtureMustRejectUnsafeDereferenceAndAcceptOptionalAccess() {
        GenerationWorkspace workspace = vueWorkspace("runtime");
        GenerationBenchmarkValidationEngine engine = engine(new RuntimeUndefinedBenchmarkRule(inspector));
        GenerationBenchmarkValidationPlan plan = engine.prepare(
                task("edit_runtime_error", "AGENT_EDIT"), workspace);

        GenerationBenchmarkQualityEvidence beforeRepair = engine.evaluate(plan);
        assertFalse(beforeRepair.passed(GenerationBenchmarkQualityDimension.FUNCTIONAL));

        String path = "src/benchmark/BenchmarkRuntimeProbe.vue";
        String repaired = inspector.readUtf8(workspace.frontendRootPath(), path)
                .replace("benchmarkUser.name", "benchmarkUser?.name");
        inspector.writeUtf8(workspace.frontendRootPath(), path, repaired);

        assertTrue(engine.evaluate(plan).passed(GenerationBenchmarkQualityDimension.FUNCTIONAL));
    }

    @Test
    void styleAndSearchFixturesMustRequireRequestedBehaviors() {
        GenerationWorkspace styleWorkspace = vueWorkspace("style");
        GenerationBenchmarkValidationEngine styleEngine = engine(new StyleEditBenchmarkRule(inspector));
        GenerationBenchmarkValidationPlan stylePlan = styleEngine.prepare(
                task("edit_style", "LIGHT_EDIT"), styleWorkspace);
        String stylePath = "src/benchmark/BenchmarkStyleProbe.vue";
        String style = inspector.readUtf8(styleWorkspace.frontendRootPath(), stylePath)
                .replace("gap: 4px", "gap: 12px")
                .replace("#ef4444", "#2563eb");
        inspector.writeUtf8(styleWorkspace.frontendRootPath(), stylePath, style);
        assertTrue(styleEngine.evaluate(stylePlan)
                .passed(GenerationBenchmarkQualityDimension.FUNCTIONAL));

        GenerationWorkspace searchWorkspace = vueWorkspace("search");
        GenerationBenchmarkValidationEngine searchEngine = engine(new SearchPaginationBenchmarkRule(inspector));
        GenerationBenchmarkValidationPlan searchPlan = searchEngine.prepare(
                task("edit_search_pagination", "AGENT_EDIT"), searchWorkspace);
        assertFalse(searchEngine.evaluate(searchPlan)
                .passed(GenerationBenchmarkQualityDimension.FUNCTIONAL));
        String searchPath = "src/benchmark/BenchmarkProductTable.vue";
        String search = inspector.readUtf8(searchWorkspace.frontendRootPath(), searchPath)
                .replace("v-for=\"product in products\"", "v-for=\"product in pagedProducts\"")
                .replace("<h2>商品列表</h2>", """
                        <h2>商品列表</h2>
                        <input v-model="keyword" type="search" />
                        <button @click="currentPage--">上一页</button>
                        <button @click="currentPage++">下一页</button>
                        """)
                .replace("const products =", """
                        import { computed, ref } from 'vue'
                        const keyword = ref('')
                        const currentPage = ref(1)
                        const pageSize = 2
                        const products =""")
                .replace("</script>", """
                        const filteredProducts = computed(() => products.filter(product =>
                          product.name.toLowerCase().includes(keyword.value.toLowerCase())))
                        const pagedProducts = computed(() => filteredProducts.value.slice(
                          (currentPage.value - 1) * pageSize,
                          currentPage.value * pageSize))
                        </script>
                        """);
        inspector.writeUtf8(searchWorkspace.frontendRootPath(), searchPath, search);
        assertTrue(searchEngine.evaluate(searchPlan)
                .passed(GenerationBenchmarkQualityDimension.FUNCTIONAL));
    }

    @Test
    void deleteModuleFixtureMustRequireFileAndReferenceRemoval() throws Exception {
        GenerationWorkspace workspace = vueWorkspace("delete");
        GenerationBenchmarkValidationEngine engine = engine(new DeleteModuleBenchmarkRule(inspector));
        GenerationBenchmarkValidationPlan plan = engine.prepare(
                task("edit_delete_module", "AGENT_EDIT"), workspace);
        String componentPath = "src/benchmark/LegacyStatistics.vue";
        assertTrue(inspector.exists(workspace.frontendRootPath(), componentPath));

        Files.delete(inspector.resolve(workspace.frontendRootPath(), componentPath));
        String app = inspector.readUtf8(workspace.frontendRootPath(), "src/App.vue")
                .replace("  <LegacyStatistics />\n", "")
                .replace("import LegacyStatistics from './benchmark/LegacyStatistics.vue'\n", "");
        inspector.writeUtf8(workspace.frontendRootPath(), "src/App.vue", app);

        assertTrue(engine.evaluate(plan).passed(GenerationBenchmarkQualityDimension.FUNCTIONAL));
    }

    @Test
    void inspectorMustRejectPathsOutsideWorkspace() {
        GenerationWorkspace workspace = vueWorkspace("path-safety");

        assertThrows(IllegalArgumentException.class,
                () -> inspector.writeUtf8(workspace.frontendRootPath(), "../escape.txt", "bad"));
    }

    @Test
    void runtimeGraderFailureMustFailClosedForEveryDeclaredDimension() {
        GenerationBenchmarkRuntimeGrader failingGrader = new GenerationBenchmarkRuntimeGrader() {
            @Override
            public String id() {
                return "failing_browser";
            }

            @Override
            public List<GenerationBenchmarkQualityDimension> dimensions() {
                return List.of(
                        GenerationBenchmarkQualityDimension.RUNTIME,
                        GenerationBenchmarkQualityDimension.VISUAL
                );
            }

            @Override
            public boolean supports(GenerationBenchmarkTask task) {
                return true;
            }

            @Override
            public List<GenerationBenchmarkRuleResult> evaluate(
                    GenerationBenchmarkRuntimeContext context
            ) {
                throw new IllegalStateException("probe failed");
            }
        };
        GenerationBenchmarkValidationEngine engine = new GenerationBenchmarkValidationEngine(
                List.of(new WorkspaceStructuralBenchmarkRule(inspector)),
                List.of(failingGrader),
                inspector,
                GenerationBenchmarkGraderMetricsCollector.noOp()
        );
        GenerationBenchmarkValidationPlan plan = engine.prepare(
                task("runtime", "CREATE"), vueWorkspace("runtime-grader"), 9L);

        GenerationBenchmarkQualityEvidence evidence = engine.evaluate(plan);

        assertFalse(evidence.passed(GenerationBenchmarkQualityDimension.RUNTIME));
        assertFalse(evidence.passed(GenerationBenchmarkQualityDimension.VISUAL));
        assertTrue(evidence.violations().stream()
                .filter(value -> value.contains("grader_execution_failed"))
                .count() >= 2);
    }

    private GenerationBenchmarkValidationEngine engine(GenerationBenchmarkValidationRule... taskRules) {
        List<GenerationBenchmarkValidationRule> rules = new ArrayList<>(List.of(
                new WorkspaceStructuralBenchmarkRule(inspector),
                new EditDiffScopeBenchmarkRule(inspector)
        ));
        rules.addAll(Arrays.asList(taskRules));
        return new GenerationBenchmarkValidationEngine(rules, inspector);
    }

    private GenerationBenchmarkTask task(String id, String mode) {
        return new GenerationBenchmarkTask(id, mode, "vue_project", "benchmark", "build");
    }

    private GenerationWorkspace vueWorkspace(String name) {
        Path root = temporaryDirectory.resolve(name).toAbsolutePath().normalize();
        inspector.writeUtf8(root, "package.json", "{\"scripts\":{\"build\":\"vite build\"}}");
        inspector.writeUtf8(root, "src/App.vue", """
                <template>
                  <router-view />
                </template>

                <script setup lang="ts">
                </script>
                """);
        inspector.writeUtf8(root, "src/data/siteData.ts", """
                export const hero = {
                  title: '原始标题',
                  description: 'benchmark'
                }
                """);
        return new GenerationWorkspace(
                101L,
                CodeGenTypeEnum.VUE_PROJECT,
                root,
                root,
                true,
                root,
                null,
                Set.of(),
                Set.of()
        );
    }
}
