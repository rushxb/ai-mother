package com.rush.rushaicodemother.architecture;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 约束所有运行时终态先形成持久 effect，资源 adapter 只由 effect 消费。 */
class GenerationTerminalEffectCoverageArchitectureTest {

    @Test
    void ownedUnownedAndExpiredTransitionsMustAllFinalizeATerminalIntent() throws Exception {
        String transaction = source(
                "orchestration/finalization/GenerationTaskFinalizationTransaction.java");
        String runtimeMapper = source("mapper/GenerationTaskRuntimeMapper.java");

        assertTrue(transaction.contains(
                "taskRepository.prepareFinalizationIntent(command, Instant.now())"),
                "owned runtime 终态必须先准备可重放 effect");
        assertTrue(runtimeMapper.contains("terminalIntentFinalizedAt = CASE")
                        && runtimeMapper.contains(
                        "WHEN terminalIntentExecutionEpoch = executionEpoch"),
                "owned 完成必须在同一个 SQL 中确认 effect");
        assertTrue(occurrences(runtimeMapper,
                "terminalIntentFinalizedAt = #{completedAt}") >= 2,
                "unowned 和 expired lease 终态必须原子写入 effect");
    }

    @Test
    void finalizerMustNotDuplicateResourceAdaptersOwnedByTheOutboxConsumer() throws Exception {
        String finalizer = source(
                "orchestration/finalization/GenerationTaskFinalizer.java");
        String effectConsumer = source(
                "orchestration/finalization/GenerationTerminalEffectService.java");

        assertFalse(finalizer.contains("stopForTerminal("));
        assertFalse(finalizer.contains("executionWorkspaceService.clear("));
        assertTrue(effectConsumer.contains("previewLifecycle.stopForTerminal("));
        assertTrue(effectConsumer.contains("workspaceService.clear("));
        assertTrue(effectConsumer.contains("generationEventStream.complete("));
    }

    private String source(String relativePath) throws Exception {
        return Files.readString(Path.of(
                "src", "main", "java", "com", "rush", "rushaicodemother", relativePath));
    }

    private int occurrences(String content, String needle) {
        return content.split(java.util.regex.Pattern.quote(needle), -1).length - 1;
    }
}
