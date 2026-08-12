package com.rush.rushaicodemother.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** 防止同步管线绕过统一执行器重复完成任务、重复扣费或遗漏终态摘要。 */
class SynchronousGenerationCompletionBoundaryArchitectureTest {

    private static final Path ORCHESTRATION_ROOT = Path.of(
            "src", "main", "java", "com", "rush", "rushaicodemother", "orchestration");
    private static final Path COMPLETION_OWNER = ORCHESTRATION_ROOT.resolve("finalization");

    @Test
    void generationRoutesMustLeaveTerminalPersistenceToFinalizer() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path sourceRoot : List.of(
                ORCHESTRATION_ROOT.resolve("pipeline"),
                ORCHESTRATION_ROOT.resolve("edit"),
                ORCHESTRATION_ROOT.resolve("heavy"),
                ORCHESTRATION_ROOT.resolve("runtime"))) {
            try (var sources = Files.walk(sourceRoot)) {
                for (Path source : sources.filter(this::isJavaSource).toList()) {
                    if (source.startsWith(COMPLETION_OWNER)) {
                        continue;
                    }
                    String content = Files.readString(source);
                    if (content.contains(".completeGeneration(")
                            || content.contains(".completeGenerationAndCharge(")
                            || content.contains(".persistOwnedCompletion(")
                            || content.contains(".persistUnownedCompletion(")) {
                        violations.add(source.toString());
                    }
                }
            }
        }

        assertTrue(violations.isEmpty(),
                "同步生成终态只能由 GenerationTaskFinalizer 提交，发现重复完成入口：\n - "
                        + String.join("\n - ", violations));
    }

    private boolean isJavaSource(Path path) {
        return Files.isRegularFile(path) && path.getFileName().toString().endsWith(".java");
    }
}
