package com.rush.rushaicodemother.orchestration.edit;

import cn.hutool.core.io.FileUtil;
import com.rush.rushaicodemother.config.PatchExecutionProperties;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.patch.PatchOperation;
import com.rush.rushaicodemother.orchestration.patch.PatchWorkspaceFileService;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentEditBackendValidationServiceTest {

    @Test
    void shouldValidateGoFilePackageAndBraces() throws Exception {
        Path root = workspaceRoot("go");
        Files.createDirectories(root.resolve("cmd/server"));
        Files.writeString(root.resolve("cmd/server/main.go"), "func main() {\n");

        BackgroundValidationService.ValidationResult result = service(new PatchExecutionProperties()).validate(
                "task-go",
                workspace(root, CodeGenTypeEnum.BACKEND_PROJECT),
                List.of(PatchOperation.modify("cmd/server/main.go", "ignored"))
        );

        assertEquals("failed", result.status());
    }

    @Test
    void shouldPassSimpleBackendValidation() throws Exception {
        Path root = workspaceRoot("pass");
        Files.createDirectories(root.resolve("cmd/server"));
        Files.writeString(root.resolve("cmd/server/main.go"), """
                package main

                func main() {
                }
                """);

        BackgroundValidationService.ValidationResult result = service(new PatchExecutionProperties()).validate(
                "task-pass",
                workspace(root, CodeGenTypeEnum.BACKEND_PROJECT),
                List.of(PatchOperation.modify("cmd/server/main.go", "ignored"))
        );

        assertEquals("success", result.status());
    }

    @Test
    void shouldResolveBackendPrefixAgainstFullStackBackendRoot() throws Exception {
        Path root = workspaceRoot("full-stack");
        Path backendRoot = root.resolve("backend");
        Files.createDirectories(backendRoot.resolve("cmd/server"));
        Files.writeString(backendRoot.resolve("cmd/server/main.go"), "package main\nfunc main() {}\n");

        GenerationWorkspace workspace = new GenerationWorkspace(
                1L,
                CodeGenTypeEnum.FULL_STACK_PROJECT,
                root,
                root.toAbsolutePath().normalize(),
                true,
                root.resolve("frontend"),
                backendRoot,
                Set.of(),
                Set.of("go", "sql", "mod")
        );
        BackgroundValidationService.ValidationResult result = service(new PatchExecutionProperties()).validate(
                "task-full-stack",
                workspace,
                List.of(PatchOperation.modify("backend/cmd/server/main.go", "ignored"))
        );

        assertEquals("success", result.status());
    }

    @Test
    void shouldNotExposeFileReadExceptionDetails() throws Exception {
        Path root = workspaceRoot("invalid-utf8");
        Files.createDirectories(root.resolve("cmd/server"));
        Files.write(root.resolve("cmd/server/main.go"), new byte[]{(byte) 0xC3, (byte) 0x28});

        BackgroundValidationService.ValidationResult result = service(new PatchExecutionProperties()).validate(
                "task-invalid-utf8",
                workspace(root, CodeGenTypeEnum.BACKEND_PROJECT),
                List.of(PatchOperation.modify("cmd/server/main.go", "ignored"))
        );

        assertEquals("failed", result.status());
        assertTrue(result.message().contains("cmd/server/main.go:读取失败"));
        assertFalse(result.message().contains("CharacterCodingException"));
        assertFalse(result.message().contains("invalid_utf8_content"));
    }

    @Test
    void shouldRejectParentTraversalWithoutReadingOutsideWorkspace() throws Exception {
        Path root = workspaceRoot("traversal");
        Path outside = root.getParent().resolve("outside.go");
        Files.writeString(outside, "package outside\n");

        BackgroundValidationService.ValidationResult result = service(new PatchExecutionProperties()).validate(
                "task-traversal",
                workspace(root, CodeGenTypeEnum.BACKEND_PROJECT),
                List.of(PatchOperation.modify("../outside.go", "ignored"))
        );

        assertEquals("failed", result.status());
        assertTrue(result.message().contains("../outside.go:读取失败"));
        assertFalse(result.message().contains("package outside"));
    }

    @Test
    void shouldRejectSymbolicLinkFile() throws Exception {
        Path root = workspaceRoot("symbolic-link");
        Path outside = Files.createTempFile("backend-validation-outside", ".go");
        Files.writeString(outside, "package outside\n");
        Path link = root.resolve("linked.go");
        createSymbolicLinkOrSkip(link, outside);

        BackgroundValidationService.ValidationResult result = service(new PatchExecutionProperties()).validate(
                "task-link",
                workspace(root, CodeGenTypeEnum.BACKEND_PROJECT),
                List.of(PatchOperation.modify("linked.go", "ignored"))
        );

        assertEquals("failed", result.status());
        assertTrue(result.message().contains("linked.go:读取失败"));
    }

    @Test
    void shouldRejectOversizedBackendFile() throws Exception {
        Path root = workspaceRoot("oversized");
        Files.writeString(root.resolve("large.sql"), "x".repeat(1_025));
        PatchExecutionProperties properties = new PatchExecutionProperties();
        properties.setMaxReadableFileBytes(1_024);

        BackgroundValidationService.ValidationResult result = service(properties).validate(
                "task-oversized",
                workspace(root, CodeGenTypeEnum.BACKEND_PROJECT),
                List.of(PatchOperation.modify("large.sql", "ignored"))
        );

        assertEquals("failed", result.status());
        assertTrue(result.message().contains("large.sql:读取失败"));
        assertFalse(result.message().contains("target_file_too_large"));
    }

    @Test
    void shouldSkipDeletedAndNullOperations() throws Exception {
        Path root = workspaceRoot("delete");

        BackgroundValidationService.ValidationResult result = service(new PatchExecutionProperties()).validate(
                "task-delete",
                workspace(root, CodeGenTypeEnum.BACKEND_PROJECT),
                Arrays.asList(null, PatchOperation.delete("obsolete.go"))
        );

        assertEquals("success", result.status());
    }

    @Test
    void shouldRejectOperationListsAboveConfiguredLimit() throws Exception {
        Path root = workspaceRoot("operation-limit");
        PatchExecutionProperties properties = new PatchExecutionProperties();
        properties.setMaxOperations(1);

        BackgroundValidationService.ValidationResult result = service(properties).validate(
                "task-operation-limit",
                workspace(root, CodeGenTypeEnum.BACKEND_PROJECT),
                List.of(PatchOperation.modify("one.go", "ignored"), PatchOperation.modify("two.go", "ignored"))
        );

        assertEquals("failed", result.status());
        assertEquals("后端补丁数量超过校验上限", result.message());
    }

    private AgentEditBackendValidationService service(PatchExecutionProperties properties) {
        return new AgentEditBackendValidationService(new PatchWorkspaceFileService(properties), properties);
    }

    private Path workspaceRoot(String name) throws IOException {
        Path root = Path.of("target", "test-workspaces", "agent-edit-backend-validation", name);
        FileUtil.del(root.toFile());
        Files.createDirectories(root);
        return root;
    }

    private GenerationWorkspace workspace(Path root, CodeGenTypeEnum codeGenType) {
        return new GenerationWorkspace(
                1L,
                codeGenType,
                root,
                root.toAbsolutePath().normalize(),
                true,
                root,
                root,
                Set.of(),
                Set.of("go", "sql", "mod")
        );
    }

    private void createSymbolicLinkOrSkip(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target.toAbsolutePath());
        } catch (IOException | UnsupportedOperationException | SecurityException exception) {
            Assumptions.assumeTrue(false, "Symbolic links are unavailable: " + exception.getMessage());
        }
    }
}
