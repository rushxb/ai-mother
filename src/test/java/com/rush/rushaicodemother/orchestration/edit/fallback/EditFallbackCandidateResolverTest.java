package com.rush.rushaicodemother.orchestration.edit.fallback;

import com.rush.rushaicodemother.config.EditLocatorProperties;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.edit.EditFileCandidate;
import com.rush.rushaicodemother.orchestration.edit.EditWorkspaceFileService;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspaceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EditFallbackCandidateResolverTest {

    @TempDir
    private Path tempDir;

    @Test
    void shouldResolveFallbackCandidatesForEveryProjectType() throws Exception {
        createProjectFiles();
        EditFallbackCandidateResolver resolver = createResolver();
        Map<CodeGenTypeEnum, List<String>> expectedPaths = expectedPathsByType();

        for (Map.Entry<CodeGenTypeEnum, List<String>> expectation : expectedPaths.entrySet()) {
            List<EditFileCandidate> candidates = resolver.resolve(
                    workspace(expectation.getKey()),
                    expectation.getKey()
            );

            assertEquals(
                    expectation.getValue(),
                    candidates.stream().map(EditFileCandidate::relativePath).toList(),
                    () -> "工程类型回退候选不符合生态约定: " + expectation.getKey()
            );
            assertTrue(candidates.stream().allMatch(candidate ->
                    "fallback_entry".equals(candidate.matchType())));
        }
    }

    @Test
    void shouldRejectAdapterCandidateOutsideCanonicalWorkspace() throws Exception {
        Files.createDirectories(tempDir);
        Path outsideFile = tempDir.getParent().resolve("outside-fallback.ts");
        Files.writeString(outsideFile, "export const leaked = true;");
        EditFallbackCandidateAdapter unsafeAdapter = new EditFallbackCandidateAdapter() {
            @Override
            public Set<CodeGenTypeEnum> supportedCodeGenTypes() {
                return Set.of(CodeGenTypeEnum.VUE_PROJECT);
            }

            @Override
            public List<Path> candidatePaths(GenerationWorkspace workspace) {
                return List.of(outsideFile);
            }
        };
        EditFallbackCandidateResolver resolver = new EditFallbackCandidateResolver(
                List.of(unsafeAdapter),
                new EditWorkspaceFileService(new EditLocatorProperties())
        );

        List<EditFileCandidate> candidates = resolver.resolve(
                workspace(CodeGenTypeEnum.VUE_PROJECT),
                CodeGenTypeEnum.VUE_PROJECT
        );

        assertTrue(candidates.isEmpty());
        Files.deleteIfExists(outsideFile);
    }

    @Test
    void shouldFailFastWhenAdapterDoesNotDeclareSupportedType() {
        EditFallbackCandidateAdapter invalidAdapter = new EditFallbackCandidateAdapter() {
            @Override
            public Set<CodeGenTypeEnum> supportedCodeGenTypes() {
                return Set.of();
            }

            @Override
            public List<Path> candidatePaths(GenerationWorkspace workspace) {
                return List.of();
            }
        };

        assertThrows(
                IllegalStateException.class,
                () -> new EditFallbackCandidateResolver(
                        List.of(invalidAdapter),
                        new EditWorkspaceFileService(new EditLocatorProperties())
                )
        );
    }

    private EditFallbackCandidateResolver createResolver() {
        return new EditFallbackCandidateResolver(
                List.of(
                        new StaticWebEditFallbackCandidateAdapter(),
                        new MultiFileEditFallbackCandidateAdapter(),
                        new VueEditFallbackCandidateAdapter(),
                        new GoBackendEditFallbackCandidateAdapter()
                ),
                new EditWorkspaceFileService(new EditLocatorProperties())
        );
    }

    private void createProjectFiles() throws Exception {
        Files.createDirectories(tempDir.resolve("src"));
        Files.createDirectories(tempDir.resolve("cmd/server"));
        Files.createDirectories(tempDir.resolve("frontend/src"));
        Files.createDirectories(tempDir.resolve("backend/cmd/server"));
        for (String relativePath : List.of(
                "index.html",
                "style.css",
                "script.js",
                "src/App.vue",
                "src/main.ts",
                "src/main.js",
                "cmd/server/main.go",
                "go.mod",
                "frontend/src/App.vue",
                "frontend/src/main.ts",
                "frontend/src/main.js",
                "backend/cmd/server/main.go",
                "backend/go.mod"
        )) {
            Files.writeString(tempDir.resolve(relativePath), "test");
        }
    }

    private Map<CodeGenTypeEnum, List<String>> expectedPathsByType() {
        EnumMap<CodeGenTypeEnum, List<String>> expected = new EnumMap<>(CodeGenTypeEnum.class);
        expected.put(CodeGenTypeEnum.HTML, List.of("index.html"));
        expected.put(CodeGenTypeEnum.MULTI_FILE, List.of("index.html", "style.css", "script.js"));
        expected.put(CodeGenTypeEnum.VUE_PROJECT, List.of("src/App.vue", "src/main.ts", "src/main.js"));
        expected.put(CodeGenTypeEnum.BACKEND_PROJECT, List.of("cmd/server/main.go", "go.mod"));
        expected.put(CodeGenTypeEnum.FULL_STACK_PROJECT, List.of(
                "frontend/src/App.vue",
                "frontend/src/main.ts",
                "frontend/src/main.js",
                "backend/cmd/server/main.go",
                "backend/go.mod"
        ));
        return expected;
    }

    private GenerationWorkspace workspace(CodeGenTypeEnum type) {
        Path root = tempDir.toAbsolutePath().normalize();
        Path frontendRoot = type == CodeGenTypeEnum.FULL_STACK_PROJECT
                ? root.resolve("frontend")
                : root;
        Path backendRoot = type == CodeGenTypeEnum.FULL_STACK_PROJECT
                ? root.resolve("backend")
                : root;
        return new GenerationWorkspace(
                1L,
                type,
                root,
                root,
                true,
                frontendRoot,
                backendRoot,
                GenerationWorkspaceService.HIDDEN_FILE_NAMES,
                GenerationWorkspaceService.EDITABLE_EXTENSIONS
        );
    }
}
