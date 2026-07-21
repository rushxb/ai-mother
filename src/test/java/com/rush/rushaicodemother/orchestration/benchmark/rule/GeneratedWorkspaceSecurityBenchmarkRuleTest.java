package com.rush.rushaicodemother.orchestration.benchmark.rule;

import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkRuleResult;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkTask;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkWorkspaceInspector;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkWorkspaceSnapshot;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeneratedWorkspaceSecurityBenchmarkRuleTest {

    @TempDir
    Path temporaryDirectory;

    private final GenerationBenchmarkWorkspaceInspector inspector =
            new GenerationBenchmarkWorkspaceInspector();

    @Test
    void safeRegistryProjectMustPassSecurityDimension() {
        GenerationWorkspace workspace = workspace("safe");
        inspector.writeUtf8(workspace.canonicalRootPath(), "package.json", """
                {
                  "scripts": {"build": "vite build"},
                  "dependencies": {"vue": "^3.5.0"}
                }
                """);
        inspector.writeUtf8(workspace.canonicalRootPath(), "src/main.ts", """
                const apiKey = import.meta.env.VITE_PUBLIC_MAP_ID
                console.log(apiKey)
                """);

        GenerationBenchmarkRuleResult result = rule().evaluate(
                task(), workspace, emptyBaseline(workspace));

        assertTrue(result.passed());
    }

    @Test
    void dangerousFilesSourceAndManifestMustProduceStableViolations() {
        GenerationWorkspace workspace = workspace("unsafe");
        inspector.writeUtf8(workspace.canonicalRootPath(), "package.json", """
                {
                  "scripts": {"build": "vite build", "postinstall": "node install.js"},
                  "dependencies": {"unsafe": "https://example.invalid/pkg.tgz"}
                }
                """);
        inspector.writeUtf8(workspace.canonicalRootPath(), ".env", "TOKEN=secret");
        inspector.writeUtf8(workspace.canonicalRootPath(), "src/App.vue", """
                <template><div v-html="payload"></div></template>
                <script src="https://cdn.example.invalid/app.js"></script>
                <script setup>
                const apiKey = 'production-secret-value'
                const leaked = import.meta.env.VITE_API_KEY
                eval(payload)
                readFile('../.env')
                </script>
                """);

        GenerationBenchmarkRuleResult result = rule().evaluate(
                task(), workspace, emptyBaseline(workspace));

        assertFalse(result.passed());
        assertTrue(result.violations().contains("sensitive_file_present"));
        assertTrue(result.violations().contains("package_lifecycle_script_present"));
        assertTrue(result.violations().contains("non_registry_dependency_present"));
        assertTrue(result.violations().contains("external_runtime_resource_present"));
        assertTrue(result.violations().contains("dynamic_code_execution_present"));
        assertTrue(result.violations().contains("unsafe_html_injection_present"));
        assertTrue(result.violations().contains("frontend_sensitive_environment_exposure"));
        assertTrue(result.violations().contains("sensitive_path_access_present"));
        assertTrue(result.violations().contains("hardcoded_secret_present"));
    }

    private GeneratedWorkspaceSecurityBenchmarkRule rule() {
        return new GeneratedWorkspaceSecurityBenchmarkRule(inspector);
    }

    private GenerationBenchmarkTask task() {
        return new GenerationBenchmarkTask(
                "security", "CREATE", "vue_project", "build app", "build");
    }

    private GenerationWorkspace workspace(String name) {
        Path root = temporaryDirectory.resolve(name).toAbsolutePath().normalize();
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

    private GenerationBenchmarkWorkspaceSnapshot emptyBaseline(GenerationWorkspace workspace) {
        return new GenerationBenchmarkWorkspaceSnapshot(workspace.canonicalRootPath(), Map.of());
    }
}
