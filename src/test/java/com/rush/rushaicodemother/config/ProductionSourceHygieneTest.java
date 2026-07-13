package com.rush.rushaicodemother.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductionSourceHygieneTest {

    private static final List<String> FORBIDDEN_CONSOLE_PATTERNS = List.of(
            "System.out.",
            "System.err.",
            ".printStackTrace("
    );

    @Test
    void productionSourcesMustNotWriteDirectlyToProcessConsole() throws IOException {
        Path projectBaseDir = Path.of(System.getProperty("projectBaseDir")).toAbsolutePath().normalize();
        Path mainSourceRoot = projectBaseDir.resolve("src/main/java");
        List<String> violations = new ArrayList<>();

        try (var sourceFiles = Files.walk(mainSourceRoot)) {
            sourceFiles
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .forEach(path -> inspectSourceFile(mainSourceRoot, path, violations));
        }

        assertTrue(
                violations.isEmpty(),
                () -> "生产源码不得直接写入 stdout/stderr 或调用 printStackTrace；请使用结构化日志：\n"
                        + String.join("\n", violations)
        );
    }

    private void inspectSourceFile(Path sourceRoot, Path sourceFile, List<String> violations) {
        try {
            List<String> lines = Files.readAllLines(sourceFile, StandardCharsets.UTF_8);
            for (int index = 0; index < lines.size(); index++) {
                String line = lines.get(index);
                if (FORBIDDEN_CONSOLE_PATTERNS.stream().anyMatch(line::contains)) {
                    violations.add(sourceRoot.relativize(sourceFile) + ":" + (index + 1));
                }
            }
        } catch (IOException exception) {
            throw new SourceInspectionException("无法读取生产源码：" + sourceFile, exception);
        }
    }

    private static final class SourceInspectionException extends RuntimeException {

        private SourceInspectionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
