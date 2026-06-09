package com.rush.rushaicodemother.orchestration.patch;

import cn.hutool.core.io.FileUtil;
import com.rush.rushaicodemother.monitor.GenerationOrchestrationMetricsCollector;
import com.rush.rushaicodemother.orchestration.artifact.ChangePlan;
import com.rush.rushaicodemother.orchestration.artifact.GenerationArtifact;
import com.rush.rushaicodemother.orchestration.artifact.PatchApplyResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerationPatchApplyServiceTest {

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final GenerationPatchApplyService service =
            new GenerationPatchApplyService(new GenerationOrchestrationMetricsCollector(meterRegistry));

    @Test
    void shouldApplyValidatedFileOperationsInsideChangePlan() throws Exception {
        Path root = cleanTestRoot("apply");
        Files.createDirectories(root.resolve("src"));
        Files.writeString(root.resolve("src/App.vue"), "<template>old</template>\n");
        Files.writeString(root.resolve("src/style.css"), ".app { color: red; }\n");
        Files.writeString(root.resolve("src/Remove.vue"), "remove me\n");
        ChangePlan changePlan = new ChangePlan(
                "v1",
                "component_patch",
                List.of("src/New.vue"),
                List.of("src/App.vue", "src/style.css"),
                List.of("src/Remove.vue"),
                List.of("src"),
                "review_only",
                "manual_retry_without_snapshot"
        );

        PatchApplyResult result = service.apply(1L, "task-1", root, changePlan, List.of(
                PatchOperation.add("src/New.vue", "<template>new</template>\n"),
                PatchOperation.replace("src/App.vue", "old", "fresh"),
                PatchOperation.modify("src/style.css", ".app { color: blue; }\n"),
                PatchOperation.delete("src/Remove.vue")
        ));

        assertEquals("applied", result.status());
        assertEquals(4, result.plannedOperationCount());
        assertEquals(4, result.appliedOperationCount());
        assertTrue(Files.readString(root.resolve("src/New.vue")).contains("new"));
        assertTrue(Files.readString(root.resolve("src/App.vue")).contains("fresh"));
        assertTrue(Files.readString(root.resolve("src/style.css")).contains("blue"));
        assertFalse(Files.exists(root.resolve("src/Remove.vue")));
        assertEquals("local_patch_executor", result.toPayload().get("provider"));
    }

    @Test
    void shouldRejectOperationOutsideChangePlanBeforeWritingAnything() throws Exception {
        Path root = cleanTestRoot("outside-plan");
        Files.createDirectories(root.resolve("src"));
        Files.writeString(root.resolve("src/App.vue"), "old\n");
        ChangePlan changePlan = new ChangePlan(
                "v1",
                "component_patch",
                List.of("src/Allowed.vue"),
                List.of(),
                List.of(),
                List.of("src"),
                "review_only",
                "manual_retry_without_snapshot"
        );

        PatchApplyResult result = service.apply(2L, "task-2", root, changePlan, List.of(
                PatchOperation.add("src/Allowed.vue", "allowed\n"),
                PatchOperation.add("src/Unexpected.vue", "unexpected\n")
        ));

        assertEquals("rejected", result.status());
        assertEquals("patch_operation_validation_failed", result.reason());
        assertTrue(result.rejectedOperations().contains("add:src/Unexpected.vue:outside_change_plan"));
        assertFalse(Files.exists(root.resolve("src/Allowed.vue")));
        assertFalse(Files.exists(root.resolve("src/Unexpected.vue")));
    }

    @Test
    void shouldRejectDirtyPathAndKeepExistingFileUnchanged() throws Exception {
        Path root = cleanTestRoot("dirty-path");
        Files.createDirectories(root.resolve("src"));
        Files.writeString(root.resolve("src/App.vue"), "old\n");
        ChangePlan changePlan = new ChangePlan(
                "v1",
                "component_patch",
                List.of(),
                List.of("src/App.vue"),
                List.of(),
                List.of("src"),
                "review_only",
                "manual_retry_without_snapshot"
        );

        PatchApplyResult result = service.apply(3L, "task-3", root, changePlan, List.of(
                PatchOperation.modify("../escape.txt", "bad\n")
        ));

        assertEquals("rejected", result.status());
        assertTrue(result.rejectedOperations().contains("modify::invalid_path"));
        assertEquals("old\n", Files.readString(root.resolve("src/App.vue")));
        assertFalse(Files.exists(root.getParent().resolve("escape.txt")));
    }

    @Test
    void shouldSkipWhenChangePlanArtifactIsMissing() throws Exception {
        Path root = cleanTestRoot("missing-plan");
        Files.createDirectories(root);

        PatchApplyResult result = service.apply(4L, "task-4", root, (GenerationArtifact) null, List.of(
                PatchOperation.add("src/New.vue", "new\n")
        ));

        assertEquals("skipped", result.status());
        assertEquals("change_plan_missing", result.reason());
    }

    @Test
    void shouldApplyFromChangePlanArtifact() throws Exception {
        Path root = cleanTestRoot("artifact");
        Files.createDirectories(root.resolve("src"));
        GenerationArtifact artifact = GenerationArtifact.of("change_plan", "test", "plan", Map.of(
                "schemaVersion", "v1",
                "changeScope", "single_file_patch",
                "addFiles", List.of("src/New.vue"),
                "modifyFiles", List.of(),
                "deleteFiles", List.of(),
                "impactedModules", List.of("src"),
                "validationLevel", "review_only",
                "rollbackStrategy", "manual_retry_without_snapshot"
        ));

        PatchApplyResult result = service.apply(5L, "task-5", root, artifact, List.of(
                PatchOperation.add("src/New.vue", "new\n")
        ));

        assertEquals("applied", result.status());
        assertTrue(Files.exists(root.resolve("src/New.vue")));
        assertEquals(1, meterRegistry.find("generation_orchestration_patch_apply_total")
                .tag("provider", "local_patch_executor")
                .tag("status", "applied")
                .tag("reason", "unknown")
                .counter()
                .count(), 0.001);
    }

    @Test
    void shouldRejectUndeclaredBareImportWithoutWritingFile() throws Exception {
        Path root = cleanTestRoot("undeclared-import");
        Files.createDirectories(root.resolve("src"));
        Files.writeString(root.resolve("package.json"), """
                {
                  "dependencies": {
                    "vue": "^3.5.0"
                  }
                }
                """);
        Files.writeString(root.resolve("src/ShowcasePage.vue"), """
                <script setup>
                import { Star } from 'vue'
                </script>
                """);

        PatchApplyResult result = service.applyWithoutChangePlan(6L, "task-6", root, List.of(
                PatchOperation.modify("src/ShowcasePage.vue", """
                        <script setup>
                        import { Star } from 'lucide-vue-next'
                        </script>
                        """)
        ), "lightweight_runtime_retry");

        assertEquals("rejected", result.status());
        assertTrue(result.rejectedOperations().contains("modify:src/ShowcasePage.vue:undeclared_bare_import:lucide-vue-next"));
        assertTrue(Files.readString(root.resolve("src/ShowcasePage.vue")).contains("from 'vue'"));
    }

    @Test
    void shouldAllowPatchThatRemovesExistingUndeclaredBareImport() throws Exception {
        Path root = cleanTestRoot("remove-undeclared-import");
        Files.createDirectories(root.resolve("src/pages"));
        Files.writeString(root.resolve("package.json"), """
                {
                  "dependencies": {
                    "vue": "^3.5.0"
                  }
                }
                """);
        Files.writeString(root.resolve("src/pages/ContactPage.vue"), """
                <script setup>
                import { ref } from 'vue'
                import { Mail } from 'lucide-vue-next'
                const icon = Mail
                </script>
                <template>
                  <Mail class="icon" />
                </template>
                """);

        PatchApplyResult result = service.applyWithoutChangePlan(7L, "task-7", root, List.of(
                PatchOperation.replace(
                        "src/pages/ContactPage.vue",
                        """
                                <script setup>
                                import { ref } from 'vue'
                                import { Mail } from 'lucide-vue-next'
                                const icon = Mail
                                </script>
                                """,
                        """
                                <script setup>
                                import { ref } from 'vue'
                                const icon = 'mail'
                                </script>
                                """
                ),
                PatchOperation.replace(
                        "src/pages/ContactPage.vue",
                        "<Mail class=\"icon\" />",
                        "<span class=\"icon\">mail</span>"
                )
        ), "lightweight_edit_retry");

        assertEquals("applied", result.status());
        String content = Files.readString(root.resolve("src/pages/ContactPage.vue"));
        assertFalse(content.contains("lucide-vue-next"));
        assertTrue(content.contains("<span class=\"icon\">mail</span>"));
    }

    private Path cleanTestRoot(String caseName) {
        Path root = Path.of("target", "test-workspaces", "patch-apply-service", caseName);
        FileUtil.del(root.toFile());
        return root;
    }
}
