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
    void expiredLeaseRequeueMustAtomicallyDiscardPreviousEpochRecoveryArtifacts() throws Exception {
        String runtimeMapper = source("mapper/GenerationTaskRuntimeMapper.java");
        String requeueSql = runtimeMapper.substring(
                runtimeMapper.lastIndexOf("@Update(\"\"\"", runtimeMapper.indexOf("int requeueExpiredLease")),
                runtimeMapper.indexOf("int requeueExpiredLease"));

        assertTrue(requeueSql.contains("terminalIntentSchemaVersion = NULL")
                        && requeueSql.contains("terminalIntentPayloadJson = NULL")
                        && requeueSql.contains("terminalIntentExecutionEpoch = NULL")
                        && requeueSql.contains("terminalIntentPreparedAt = NULL")
                        && requeueSql.contains("terminalIntentFinalizedAt = NULL"),
                "恢复重派推进 executionEpoch 时必须在同一 SQL 中清除旧轮次未完成终态意图");
        assertTrue(requeueSql.contains("AND terminalIntentFinalizedAt IS NULL"),
                "已经最终化的终态意图不得被恢复重派覆盖");
        assertTrue(requeueSql.contains("publicationStatus = NULL")
                        && requeueSql.contains("publicationExecutionEpoch = NULL")
                        && requeueSql.contains("publicationPublishedAt = NULL"),
                "恢复重派必须清除已结束旧轮次的发布 journal，避免新轮次与旧指针冲突");
        assertTrue(requeueSql.contains(
                        "AND (publicationStatus IS NULL OR publicationStatus IN ('rolled_back', 'superseded'))"),
                "待对账或已提交的发布 journal 不得被恢复重派覆盖");
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
