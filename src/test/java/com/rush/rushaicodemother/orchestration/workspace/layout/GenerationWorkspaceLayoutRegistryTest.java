package com.rush.rushaicodemother.orchestration.workspace.layout;

import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GenerationWorkspaceLayoutRegistryTest {

    private final Path canonicalRoot = Path.of("workspace").toAbsolutePath().normalize();

    @Test
    void registeredAdaptersMustDescribeEveryProjectLayoutWithoutTypeBranches() {
        GenerationWorkspaceLayoutRegistry registry = new GenerationWorkspaceLayoutRegistry(List.of(
                new FrontendWorkspaceLayoutAdapter(),
                new BackendWorkspaceLayoutAdapter(),
                new FullStackWorkspaceLayoutAdapter()
        ));

        for (CodeGenTypeEnum type : List.of(
                CodeGenTypeEnum.HTML,
                CodeGenTypeEnum.MULTI_FILE,
                CodeGenTypeEnum.VUE_PROJECT)) {
            GenerationWorkspaceLayout layout = registry.resolve(type, canonicalRoot);
            assertEquals(canonicalRoot, layout.frontendRootPath());
            assertNull(layout.backendRootPath());
        }

        GenerationWorkspaceLayout backend = registry.resolve(
                CodeGenTypeEnum.BACKEND_PROJECT, canonicalRoot);
        assertNull(backend.frontendRootPath());
        assertEquals(canonicalRoot, backend.backendRootPath());

        GenerationWorkspaceLayout fullStack = registry.resolve(
                CodeGenTypeEnum.FULL_STACK_PROJECT, canonicalRoot);
        assertEquals(canonicalRoot.resolve("frontend"), fullStack.frontendRootPath());
        assertEquals(canonicalRoot.resolve("backend"), fullStack.backendRootPath());
    }

    @Test
    void duplicateTypeOwnershipMustFailFast() {
        GenerationWorkspaceLayoutAdapter duplicateVueAdapter = new GenerationWorkspaceLayoutAdapter() {
            @Override
            public Set<CodeGenTypeEnum> supportedTypes() {
                return Set.of(CodeGenTypeEnum.VUE_PROJECT);
            }

            @Override
            public GenerationWorkspaceLayout resolve(Path canonicalRootPath) {
                return new GenerationWorkspaceLayout(canonicalRootPath, null);
            }
        };

        assertThrows(IllegalStateException.class, () -> new GenerationWorkspaceLayoutRegistry(List.of(
                new FrontendWorkspaceLayoutAdapter(),
                new BackendWorkspaceLayoutAdapter(),
                new FullStackWorkspaceLayoutAdapter(),
                duplicateVueAdapter
        )));
    }

    @Test
    void adapterRoleRootsMustNotEscapeTheCanonicalWorkspace() {
        GenerationWorkspaceLayoutAdapter escapingAdapter = new GenerationWorkspaceLayoutAdapter() {
            @Override
            public Set<CodeGenTypeEnum> supportedTypes() {
                return Set.of(CodeGenTypeEnum.values());
            }

            @Override
            public GenerationWorkspaceLayout resolve(Path canonicalRootPath) {
                return new GenerationWorkspaceLayout(
                        canonicalRootPath.resolve("..").resolve("escaped"), null);
            }
        };
        GenerationWorkspaceLayoutRegistry registry =
                new GenerationWorkspaceLayoutRegistry(List.of(escapingAdapter));

        assertThrows(IllegalStateException.class,
                () -> registry.resolve(CodeGenTypeEnum.VUE_PROJECT, canonicalRoot));
    }
}
