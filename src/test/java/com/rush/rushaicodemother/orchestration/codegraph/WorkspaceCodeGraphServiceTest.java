package com.rush.rushaicodemother.orchestration.codegraph;

import cn.hutool.core.io.FileUtil;
import com.rush.rushaicodemother.config.WorkspaceFileSystemProperties;
import com.rush.rushaicodemother.infrastructure.filesystem.WorkspaceFileSystemService;
import com.rush.rushaicodemother.infrastructure.filesystem.WorkspaceFileSystemTestFactory;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorkspaceCodeGraphServiceTest {

    private final StructuredSyntaxValidationService syntaxValidationService = new StructuredSyntaxValidationService();
    private final WorkspaceCodeGraphService service = new WorkspaceCodeGraphService(
            new CodeGraphAstParser(syntaxValidationService),
            WorkspaceFileSystemTestFactory.create()
    );

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

    @Test
    void shouldNotExposeCodeGraphBuildExceptionDetails() throws Exception {
        Path root = createTempWorkspace();
        try {
            write(root, "src/App.vue", "<template><main>App</main></template>");
            CodeGraphAstParser failingParser = mock(CodeGraphAstParser.class);
            when(failingParser.parse(anyString(), anyString(), anySet()))
                    .thenThrow(new IllegalStateException("provider-api-key=secret-value"));

            WorkspaceCodeGraph graph = new WorkspaceCodeGraphService(
                    failingParser,
                    WorkspaceFileSystemTestFactory.create()
            ).build(root);

            assertEquals(List.of("code_graph_build_failed"), graph.diagnostics());
            assertFalse(graph.diagnostics().toString().contains("secret-value"));
        } finally {
            cleanup(root);
        }
    }

    @Test
    void shouldResolveImportsFromScanMetadataWithoutReadingFileSystemInParser() throws Exception {
        Path root = createTempWorkspace();
        try {
            write(root, "src/components/index.tsx", "export const Component = () => null");
            write(root, "src/pages/Home.ts", "import { Component } from '../components'");
            write(root, "internal/sample/zeta.go", "package sample");
            write(root, "internal/sample/alpha.go", "package sample");
            write(root, "cmd/main.go", "package main\nimport \"example/internal/sample\"");

            WorkspaceCodeGraph graph = service.build(root);

            assertEquals(List.of("src/components/index.tsx"), graph.importsByFile().get("src/pages/Home.ts"));
            assertEquals(List.of("internal/sample/alpha.go"), graph.importsByFile().get("cmd/main.go"));
        } finally {
            cleanup(root);
        }
    }

    @Test
    void shouldRejectRelativeImportThatEscapesWorkspace() throws Exception {
        Path root = createTempWorkspace();
        try {
            write(root, "src/pages/Home.ts", "import secret from '../../../outside-secret'");

            WorkspaceCodeGraph graph = service.build(root);

            assertTrue(graph.importsByFile().get("src/pages/Home.ts").isEmpty());
        } finally {
            cleanup(root);
        }
    }

    @Test
    void shouldReportWorkspaceScanLimitWithoutExposingExceptionDetails() throws Exception {
        Path root = createTempWorkspace();
        try {
            write(root, "src/first.ts", "export const first = true");
            write(root, "src/second.ts", "export const second = true");
            WorkspaceFileSystemProperties properties = new WorkspaceFileSystemProperties();
            properties.setMaxFiles(1);
            WorkspaceCodeGraphService limitedService = new WorkspaceCodeGraphService(
                    new CodeGraphAstParser(syntaxValidationService),
                    new WorkspaceFileSystemService(properties)
            );

            WorkspaceCodeGraph graph = limitedService.build(root);

            assertEquals(List.of("code_graph_scan_limit_exceeded"), graph.diagnostics());
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
