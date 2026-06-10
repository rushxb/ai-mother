package com.rush.rushaicodemother.orchestration.codegraph;

import cn.hutool.core.io.FileUtil;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceCodeGraphServiceTest {

    private final StructuredSyntaxValidationService syntaxValidationService = new StructuredSyntaxValidationService();
    private final WorkspaceCodeGraphService service = new WorkspaceCodeGraphService(new CodeGraphAstParser(syntaxValidationService));

    @Test
    void shouldBuildVueAndGoReferenceGraph() throws Exception {
        Path root = createTempWorkspace();
        try {
            write(root, "src/api/product.ts", "export function listProducts() { return [] }");
            write(root, "src/views/ProductView.vue", """
                    <template><main>Products</main></template>
                    <script setup lang="ts">
                    import { listProducts } from '@/api/product'
                    </script>
                    """);
            write(root, "internal/modules/product/handler.go", """
                    package product

                    import "backend-template/internal/modules/product/repository"

                    func ListProducts() {}
                    """);
            write(root, "internal/modules/product/repository/repository.go", """
                    package repository

                    type Product struct { Name string }
                    """);

            WorkspaceCodeGraph graph = service.build(root);

            assertEquals(List.of("src/views/ProductView.vue"), graph.referencedBy("src/api/product.ts"));
            assertTrue(graph.symbolsByName().containsKey("listProducts"));
            assertTrue(graph.symbolsByName().containsKey("Product"));
            assertTrue(graph.importsByFile().get("internal/modules/product/handler.go")
                    .contains("internal/modules/product/repository/repository.go"));
            assertTrue(graph.diagnostics().isEmpty());
        } finally {
            cleanup(root);
        }
    }

    @Test
    void shouldReportStructuredSyntaxDiagnostics() throws Exception {
        Path root = createTempWorkspace();
        try {
            write(root, "src/Broken.vue", """
                    <template><main>Broken</main>
                    <script setup>
                    const value = {
                    </script>
                    """);

            WorkspaceCodeGraph graph = service.build(root);

            assertFalse(graph.diagnostics().isEmpty());
            assertTrue(graph.diagnostics().stream().anyMatch(item -> item.contains("ast_")));
        } finally {
            cleanup(root);
        }
    }

    private Path createTempWorkspace() throws Exception {
        Path root = Path.of(System.getProperty("java.io.tmpdir"), "ai-code-mother-tests");
        Files.createDirectories(root);
        return Files.createTempDirectory(root, "code-graph-");
    }

    private void write(Path rootDir, String relativePath, String content) throws Exception {
        Path file = rootDir.resolve(relativePath);
        Files.createDirectories(file.getParent() == null ? rootDir : file.getParent());
        Files.writeString(file, content);
    }

    private void cleanup(Path path) {
        if (path == null) {
            return;
        }
        try {
            FileUtil.del(path.toFile());
        } catch (Exception ignored) {
        }
    }
}
