package com.rush.rushaicodemother.orchestration.edit;

import cn.hutool.core.io.FileUtil;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.patch.PatchOperation;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentEditBackendValidationServiceTest {

    private final AgentEditBackendValidationService validationService = new AgentEditBackendValidationService();

    @Test
    void shouldValidateGoFilePackageAndBraces() throws Exception {
        Path root = Path.of("target", "test-workspaces", "agent-edit-backend-validation", "go");
        FileUtil.del(root.toFile());
        Files.createDirectories(root.resolve("cmd/server"));
        Files.writeString(root.resolve("cmd/server/main.go"), "func main() {\n");

        BackgroundValidationService.ValidationResult result = validationService.validate(
                "task-go",
                workspace(root, CodeGenTypeEnum.BACKEND_PROJECT),
                List.of(PatchOperation.modify("cmd/server/main.go", "ignored"))
        );

        assertEquals("failed", result.status());
    }

    @Test
    void shouldPassSimpleBackendValidation() throws Exception {
        Path root = Path.of("target", "test-workspaces", "agent-edit-backend-validation", "pass");
        FileUtil.del(root.toFile());
        Files.createDirectories(root.resolve("cmd/server"));
        Files.writeString(root.resolve("cmd/server/main.go"), """
                package main

                func main() {
                }
                """);

        BackgroundValidationService.ValidationResult result = validationService.validate(
                "task-pass",
                workspace(root, CodeGenTypeEnum.BACKEND_PROJECT),
                List.of(PatchOperation.modify("cmd/server/main.go", "ignored"))
        );

        assertEquals("success", result.status());
    }

    @Test
    void shouldNotExposeFileReadExceptionDetails() throws Exception {
        Path root = Path.of("target", "test-workspaces", "agent-edit-backend-validation", "invalid-utf8");
        FileUtil.del(root.toFile());
        Files.createDirectories(root.resolve("cmd/server"));
        Files.write(root.resolve("cmd/server/main.go"), new byte[]{(byte) 0xC3, (byte) 0x28});

        BackgroundValidationService.ValidationResult result = validationService.validate(
                "task-invalid-utf8",
                workspace(root, CodeGenTypeEnum.BACKEND_PROJECT),
                List.of(PatchOperation.modify("cmd/server/main.go", "ignored"))
        );

        assertEquals("failed", result.status());
        assertTrue(result.message().contains("cmd/server/main.go:读取失败"));
        assertFalse(result.message().contains("MalformedInputException"));
        assertFalse(result.message().contains("Input length"));
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
                Set.of()
        );
    }
}
