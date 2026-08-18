package com.rush.rushaicodemother.orchestration.edit;

import cn.hutool.core.io.FileUtil;
import com.rush.rushaicodemother.config.EditLocatorProperties;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.edit.fallback.EditFallbackCandidateResolver;
import com.rush.rushaicodemother.orchestration.edit.fallback.GoBackendEditFallbackCandidateAdapter;
import com.rush.rushaicodemother.orchestration.edit.fallback.MultiFileEditFallbackCandidateAdapter;
import com.rush.rushaicodemother.orchestration.edit.fallback.StaticWebEditFallbackCandidateAdapter;
import com.rush.rushaicodemother.orchestration.edit.fallback.VueEditFallbackCandidateAdapter;
import com.rush.rushaicodemother.orchestration.index.WorkspaceSemanticIndexService;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspaceService;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EditFileLocatorServiceTest {

    private Path tempDir;

    @Test
    void shouldPrioritizeSelectedElementComponentOverUnrelatedFallbackFiles() throws Exception {
        tempDir = cleanTestRoot("selected-element");
        Files.createDirectories(tempDir.resolve("src/components/tres/particles"));
        Files.createDirectories(tempDir.resolve("src/pages"));
        Files.createDirectories(tempDir.resolve(".ai-code-index"));
        Files.createDirectories(tempDir.resolve("dist/assets"));
        Files.createDirectories(tempDir.resolve("node_modle/.vite"));
        Files.writeString(tempDir.resolve("src/components/ProductCard.vue"), """
                <template>
                  <article class="product-card">
                    <button class="cart-button">\u52a0\u5165\u8d2d\u7269\u8f66</button>
                  </article>
                </template>
                """);
        Files.writeString(tempDir.resolve("src/pages/MobileHomePage.vue"), """
                <template><div class="product-list"><ProductCard /></div></template>
                """);
        Files.writeString(tempDir.resolve("src/components/tres/particles/ParticleSwarm.vue"), "<template><div /></template>");
        Files.writeString(tempDir.resolve(".ai-code-index/semantic-index.json"), "{}");
        Files.writeString(tempDir.resolve("dist/assets/ProductCard.js"), "console.log('product-card')");
        Files.writeString(tempDir.resolve("node_modle/.vite/ProductCard.js"), "console.log('product-card')");
        Files.writeString(tempDir.resolve("src/App.vue"), "<template><RouterView /></template>");
        Files.writeString(tempDir.resolve("src/main.ts"), "import './styles/mobile.css'\n");

        WorkspaceSemanticIndexService semanticIndexService = semanticIndexReturning(List.of(
                ".ai-code-index/semantic-index.json",
                "dist/assets/ProductCard.js",
                "node_modle/.vite/ProductCard.js",
                "src/components/tres/particles/ParticleSwarm.vue"
        ));
        EditFileLocatorService service = createService(semanticIndexService, emptyEditState(), new EditLocatorProperties());

        List<EditFileCandidate> candidates = service.locate(workspace(), """
                \u52a0\u5165\u8d2d\u7269\u8f66\u6309\u94ae\u6837\u5f0f\u5f02\u5e38

                \u9009\u4e2d\u5143\u7d20\u4fe1\u606f\uff1a
                - \u9875\u9762\u8def\u5f84: #/
                - \u6807\u7b7e: article
                - \u9009\u62e9\u5668: div#app > div.site-wrapper > div.product-list > article.product-card
                - \u5f53\u524d\u5185\u5bb9: \u52a0\u5165\u8d2d\u7269\u8f66
                """, CodeGenTypeEnum.VUE_PROJECT);

        assertEquals("src/components/ProductCard.vue", candidates.getFirst().relativePath());
        assertEquals("selected_element", candidates.getFirst().matchType());
        assertTrue(candidates.stream().anyMatch(candidate -> "src/pages/MobileHomePage.vue".equals(candidate.relativePath())));
        assertTrue(candidates.stream().noneMatch(candidate -> candidate.relativePath().startsWith("dist/")));
        assertTrue(candidates.stream().noneMatch(candidate -> candidate.relativePath().startsWith(".ai-code-index/")));
        assertTrue(candidates.stream().noneMatch(candidate -> candidate.relativePath().startsWith("node_modle/")));
    }

    @Test
    void shouldLocateRollupImportFailureSourceFileFromAbsolutePath() throws Exception {
        tempDir = cleanTestRoot("rollup-import");
        Files.createDirectories(tempDir.resolve("src/pages"));
        Files.writeString(tempDir.resolve("src/pages/ShowcasePage.vue"), "import { Star } from 'lucide-vue-next'");
        Files.writeString(tempDir.resolve("src/main.ts"), "import './style.css'\n");

        EditFileLocatorService service = createService(
                semanticIndexReturning(List.of()), emptyEditState(), new EditLocatorProperties()
        );
        List<EditFileCandidate> candidates = service.locate(workspace(), """
                [vite]: Rollup failed to resolve import "lucide-vue-next" from "D:/workspace/src/pages/ShowcasePage.vue".
                """, CodeGenTypeEnum.VUE_PROJECT);

        assertEquals("src/pages/ShowcasePage.vue", candidates.getFirst().relativePath());
        assertEquals("explicit_path", candidates.getFirst().matchType());
    }

    @Test
    void shouldLocateSelectedElementFromTextWhenSelectorHasNoClassName() throws Exception {
        tempDir = cleanTestRoot("selected-element-text");
        Files.createDirectories(tempDir.resolve("src/components"));
        Files.writeString(tempDir.resolve("src/components/CheckoutButton.vue"),
                "<template><button>\u52a0\u5165\u8d2d\u7269\u8f66</button></template>");
        EditFileLocatorService service = createService(
                semanticIndexReturning(List.of()), emptyEditState(), new EditLocatorProperties()
        );

        List<EditFileCandidate> candidates = service.locate(workspace(), """
                \u9009\u4e2d\u5143\u7d20\u4fe1\u606f\uff1a
                - \u9009\u62e9\u5668: button:nth-child(1)
                - \u5f53\u524d\u5185\u5bb9: \u52a0\u5165\u8d2d\u7269\u8f66
                """, CodeGenTypeEnum.VUE_PROJECT);

        assertEquals("src/components/CheckoutButton.vue", candidates.getFirst().relativePath());
        assertEquals("selected_element", candidates.getFirst().matchType());
    }

    @Test
    void shouldPreserveCandidateSourcePriority() throws Exception {
        tempDir = cleanTestRoot("candidate-priority");
        Files.createDirectories(tempDir.resolve("src"));
        Files.writeString(tempDir.resolve("src/Explicit.ts"), "export const explicit = true;");
        Files.writeString(tempDir.resolve("src/Bare.vue"), "<template><div /></template>");
        Files.writeString(tempDir.resolve("src/Selected.vue"), "<template><article class=\"selected-card\" /></template>");
        Files.writeString(tempDir.resolve("src/Diagnostic.ts"), "const dup = 1;");
        Files.writeString(tempDir.resolve("src/Recent.ts"), "export const recent = true;");
        Files.writeString(tempDir.resolve("src/Semantic.ts"), "export const semantic = true;");
        Files.writeString(tempDir.resolve("src/App.vue"), "<template><RouterView /></template>");

        WorkspaceSemanticIndexService semanticIndexService = semanticIndexReturning(List.of("src/Semantic.ts"));
        when(semanticIndexService.findFilesReferencing(any(), any(), anySet(), anyInt())).thenReturn(List.of());
        EditStatePersistenceService editState = mock(EditStatePersistenceService.class);
        when(editState.getRelevantRecentFiles(any(), any(), anyInt())).thenReturn(List.of("src/Recent.ts"));
        EditFileLocatorService service = createService(semanticIndexService, editState, new EditLocatorProperties());

        List<EditFileCandidate> candidates = service.locate(workspace(), """
                Edit src/Explicit.ts and Bare.vue.
                \u9009\u4e2d\u5143\u7d20\u4fe1\u606f\uff1a
                - \u9009\u62e9\u5668: article.selected-card
                Identifier 'dup' has already been declared
                """, CodeGenTypeEnum.VUE_PROJECT);

        assertEquals(List.of(
                        "explicit_path",
                        "explicit_file_name",
                        "selected_element",
                        "duplicate_identifier",
                        "recent_modified",
                        "semantic_search",
                        "fallback_entry"
                ),
                candidates.stream().map(EditFileCandidate::matchType).toList());
    }

    @Test
    void shouldRejectExplicitPathThatEscapesWorkspaceRoot() throws Exception {
        tempDir = cleanTestRoot("path-escape");
        Files.createDirectories(tempDir.resolve("src"));
        Path outsideFile = tempDir.getParent().resolve("outside-secret.ts");
        Files.writeString(outsideFile, "export const secret = true;");
        try {
            EditFileLocatorService service = createService(
                    semanticIndexReturning(List.of()), emptyEditState(), new EditLocatorProperties()
            );
            List<EditFileCandidate> candidates = service.locate(
                    workspace(), "Please edit src/../../outside-secret.ts", CodeGenTypeEnum.VUE_PROJECT
            );

            assertTrue(candidates.stream().noneMatch(candidate -> candidate.relativePath().contains("..")));
            assertTrue(candidates.stream().noneMatch(candidate -> candidate.fileName().equals("outside-secret.ts")));
        } finally {
            Files.deleteIfExists(outsideFile);
        }
    }

    @Test
    void shouldRejectUnsafeSemanticAndRecentPaths() throws Exception {
        tempDir = cleanTestRoot("unsafe-index-results");
        Files.createDirectories(tempDir.resolve("src"));
        Files.createDirectories(tempDir.resolve(".hidden"));
        Files.writeString(tempDir.resolve("src/Safe.ts"), "export const safe = true;");
        Files.writeString(tempDir.resolve(".hidden/Secret.ts"), "export const secret = true;");
        Path outsideFile = tempDir.getParent().resolve("outside-index-secret.ts");
        Files.writeString(outsideFile, "export const secret = true;");
        try {
            WorkspaceSemanticIndexService semanticIndexService = semanticIndexReturning(List.of(
                    outsideFile.toAbsolutePath().toString(),
                    "../outside-index-secret.ts",
                    ".hidden/Secret.ts",
                    "src/Missing.ts",
                    "src/Safe.ts"
            ));
            EditStatePersistenceService editState = mock(EditStatePersistenceService.class);
            when(editState.getRelevantRecentFiles(any(), any(), anyInt())).thenReturn(List.of(
                    outsideFile.toAbsolutePath().toString(), "../outside-index-secret.ts", ".hidden/Secret.ts"
            ));
            EditFileLocatorService service = createService(semanticIndexService, editState, new EditLocatorProperties());

            List<EditFileCandidate> candidates = service.locate(workspace(), "safe change", CodeGenTypeEnum.VUE_PROJECT);

            assertEquals(List.of("src/Safe.ts"), candidates.stream().map(EditFileCandidate::relativePath).toList());
        } finally {
            Files.deleteIfExists(outsideFile);
        }
    }

    @Test
    void shouldRejectWorkspaceSymlinkPointingOutsideRoot() throws Exception {
        tempDir = cleanTestRoot("outside-symlink");
        Files.createDirectories(tempDir.resolve("src"));
        Path outsideFile = tempDir.getParent().resolve("outside-linked-secret.ts");
        Files.writeString(outsideFile, "export const secret = true;");
        Path link = tempDir.resolve("src/LinkedSecret.ts");
        try {
            createSymbolicLinkOrSkip(link, outsideFile);
            EditFileLocatorService service = createService(
                    semanticIndexReturning(List.of("src/LinkedSecret.ts")), emptyEditState(), new EditLocatorProperties()
            );

            List<EditFileCandidate> candidates = service.locate(workspace(), "linked secret", CodeGenTypeEnum.VUE_PROJECT);

            assertTrue(candidates.stream().noneMatch(candidate -> "src/LinkedSecret.ts".equals(candidate.relativePath())));
        } finally {
            Files.deleteIfExists(link);
            Files.deleteIfExists(outsideFile);
        }
    }

    @Test
    void shouldRejectContextCandidateThatEscapesWorkspaceRoot() throws Exception {
        tempDir = cleanTestRoot("context-path-escape");
        Files.createDirectories(tempDir.resolve("src"));
        Path outsideFile = tempDir.getParent().resolve("outside-context-secret.ts");
        Files.writeString(outsideFile, "export const secret = true;");
        try {
            EditLocatorProperties properties = new EditLocatorProperties();
            EditWorkspaceFileService fileService = new EditWorkspaceFileService(properties);
            EditContextPackageBuilder contextBuilder = new EditContextPackageBuilder(fileService, properties);
            EditFileCandidate escapedCandidate = new EditFileCandidate(
                    "src/../../outside-context-secret.ts", "outside-context-secret.ts", "test", 100, "test", List.of()
            );

            EditContextPackage contextPackage = contextBuilder.build(workspace(), List.of(escapedCandidate));

            assertTrue(contextPackage.isEmpty());
            assertFalse(contextPackage.fileContents().containsKey(escapedCandidate.relativePath()));
        } finally {
            Files.deleteIfExists(outsideFile);
        }
    }

    @Test
    void shouldIgnoreNullRecentFileEntriesWithoutDroppingLaterValidCandidates() throws Exception {
        tempDir = cleanTestRoot("null-recent-entry");
        Files.createDirectories(tempDir.resolve("src"));
        Files.writeString(tempDir.resolve("src/ValidCandidate.ts"), "export const valid = true;");

        EditStatePersistenceService editState = mock(EditStatePersistenceService.class);
        when(editState.getRelevantRecentFiles(any(), any(), anyInt()))
                .thenReturn(java.util.Arrays.asList(null, "src/ValidCandidate.ts"));
        EditFileLocatorService service = createService(
                semanticIndexReturning(List.of()), editState, new EditLocatorProperties()
        );

        List<EditFileCandidate> candidates = service.locate(
                workspace(), "continue from the recent edit", CodeGenTypeEnum.VUE_PROJECT
        );

        assertTrue(candidates.stream().anyMatch(candidate ->
                "src/ValidCandidate.ts".equals(candidate.relativePath())
                        && "recent_modified".equals(candidate.matchType())));
    }

    @Test
    void fullStackFallbackMustIncludeFrontendAndBackendEntryFiles() throws Exception {
        tempDir = cleanTestRoot("full-stack-fallback");
        Path frontendRoot = tempDir.resolve("frontend");
        Path backendRoot = tempDir.resolve("backend");
        Files.createDirectories(frontendRoot.resolve("src"));
        Files.createDirectories(backendRoot.resolve("cmd/server"));
        Files.writeString(frontendRoot.resolve("src/App.vue"), "<template><main /></template>");
        Files.writeString(frontendRoot.resolve("src/main.ts"), "import './App.vue';");
        Files.writeString(backendRoot.resolve("cmd/server/main.go"), "package main");
        Files.writeString(backendRoot.resolve("go.mod"), "module example.test/app");
        EditFileLocatorService service = createService(
                semanticIndexReturning(List.of()), emptyEditState(), new EditLocatorProperties()
        );

        List<EditFileCandidate> candidates = service.locate(
                fullStackWorkspace(frontendRoot, backendRoot),
                "",
                CodeGenTypeEnum.FULL_STACK_PROJECT
        );

        assertEquals(
                List.of(
                        "frontend/src/App.vue",
                        "frontend/src/main.ts",
                        "backend/cmd/server/main.go",
                        "backend/go.mod"
                ),
                candidates.stream().map(EditFileCandidate::relativePath).toList()
        );
        assertTrue(candidates.stream().allMatch(candidate ->
                "fallback_entry".equals(candidate.matchType())));
    }

    private EditFileLocatorService createService(WorkspaceSemanticIndexService semanticIndexService,
                                                 EditStatePersistenceService editState,
                                                 EditLocatorProperties properties) {
        EditWorkspaceFileService fileService = new EditWorkspaceFileService(properties);
        SelectedElementFileLocator selectedLocator = new SelectedElementFileLocator(fileService, properties);
        DiagnosticFileLocator diagnosticLocator = new DiagnosticFileLocator(semanticIndexService, fileService);
        EditFallbackCandidateResolver fallbackResolver = new EditFallbackCandidateResolver(
                List.of(
                        new StaticWebEditFallbackCandidateAdapter(),
                        new MultiFileEditFallbackCandidateAdapter(),
                        new VueEditFallbackCandidateAdapter(),
                        new GoBackendEditFallbackCandidateAdapter()
                ),
                fileService
        );
        return new EditFileLocatorService(
                semanticIndexService,
                editState,
                selectedLocator,
                diagnosticLocator,
                fileService,
                fallbackResolver,
                properties
        );
    }

    private WorkspaceSemanticIndexService semanticIndexReturning(List<String> paths) {
        WorkspaceSemanticIndexService semanticIndexService = mock(WorkspaceSemanticIndexService.class);
        when(semanticIndexService.suggestFiles(any(), any(), anyInt())).thenReturn(paths);
        when(semanticIndexService.findFilesReferencing(any(), any(), anySet(), anyInt())).thenReturn(List.of());
        return semanticIndexService;
    }

    private EditStatePersistenceService emptyEditState() {
        EditStatePersistenceService editState = mock(EditStatePersistenceService.class);
        when(editState.getRelevantRecentFiles(any(), any(), anyInt())).thenReturn(List.of());
        return editState;
    }

    private Path cleanTestRoot(String caseName) {
        Path root = Path.of("target", "test-workspaces", "edit-file-locator", caseName)
                .toAbsolutePath()
                .normalize();
        FileUtil.del(root.toFile());
        return root;
    }

    private GenerationWorkspace workspace() {
        Path root = tempDir.toAbsolutePath().normalize();
        return new GenerationWorkspace(
                1L,
                CodeGenTypeEnum.VUE_PROJECT,
                root,
                root,
                true,
                root,
                null,
                GenerationWorkspaceService.HIDDEN_FILE_NAMES,
                GenerationWorkspaceService.EDITABLE_EXTENSIONS
        );
    }

    private GenerationWorkspace fullStackWorkspace(Path frontendRoot, Path backendRoot) {
        Path root = tempDir.toAbsolutePath().normalize();
        return new GenerationWorkspace(
                1L,
                CodeGenTypeEnum.FULL_STACK_PROJECT,
                root,
                root,
                true,
                frontendRoot.toAbsolutePath().normalize(),
                backendRoot.toAbsolutePath().normalize(),
                GenerationWorkspaceService.HIDDEN_FILE_NAMES,
                GenerationWorkspaceService.EDITABLE_EXTENSIONS
        );
    }

    private void createSymbolicLinkOrSkip(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target.toAbsolutePath());
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            Assumptions.assumeTrue(false, "Symbolic links are unavailable in this test environment: " + e.getMessage());
        }
    }
}
