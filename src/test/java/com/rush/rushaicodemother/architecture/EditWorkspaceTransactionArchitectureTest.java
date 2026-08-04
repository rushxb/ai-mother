package com.rush.rushaicodemother.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 编辑协调器的快照、提交和回滚必须由显式工作区事务统一拥有。 */
class EditWorkspaceTransactionArchitectureTest {

    private static final Path EDIT_SOURCE_ROOT = Path.of(
            "src", "main", "java", "com", "rush", "rushaicodemother", "orchestration", "edit");

    @Test
    void editCoordinatorsMustUseExplicitTransactionBoundary() throws IOException {
        for (String sourceName : List.of(
                "AgentEditGenerationService.java",
                "LightweightEditService.java")) {
            String source = Files.readString(EDIT_SOURCE_ROOT.resolve(sourceName));

            assertTrue(source.contains("beginTransaction("),
                    sourceName + " 必须显式开启编辑工作区事务");
            assertTrue(source.contains("workspaceTransaction.commit()"),
                    sourceName + " 必须在验证和成功副作用完成后显式提交");
            assertTrue(source.contains("workspaceTransaction.rollback()"),
                    sourceName + " 必须在已知失败分支显式回滚");
            assertFalse(source.contains("editFileSnapshotService.capture("),
                    sourceName + " 不得绕过事务直接捕获快照");
            assertFalse(source.contains("editFileSnapshotService.captureMissing("),
                    sourceName + " 不得绕过事务直接扩展快照");
            assertFalse(source.contains("editFileSnapshotService.restore("),
                    sourceName + " 不得绕过事务直接恢复快照");
        }
    }

    @Test
    void retryHelpersMustExpandTheOwningTransactionInsteadOfReceivingRawSnapshots() throws IOException {
        String patchExecutor = Files.readString(
                EDIT_SOURCE_ROOT.resolve("LightweightEditPatchExecutor.java"));
        String runtimeValidation = Files.readString(
                EDIT_SOURCE_ROOT.resolve("LightweightRuntimeValidationService.java"));

        assertTrue(patchExecutor.contains("EditWorkspaceTransaction workspaceTransaction"));
        assertTrue(patchExecutor.contains("workspaceTransaction.include(retryOperations)"));
        assertFalse(patchExecutor.contains("EditFileSnapshotService.EditFileSnapshot editSnapshot"));

        assertTrue(runtimeValidation.contains("EditWorkspaceTransaction workspaceTransaction"));
        assertTrue(runtimeValidation.contains("workspaceTransaction::include"));
    }

    @Test
    void transactionMustAutoRollbackUnlessExplicitlyCommitted() throws IOException {
        String transaction = Files.readString(
                EDIT_SOURCE_ROOT.resolve("EditWorkspaceTransaction.java"));

        assertTrue(transaction.contains("implements AutoCloseable"));
        assertTrue(transaction.contains("if (state == State.OPEN)"));
        assertTrue(transaction.contains("rollback();"));
        assertTrue(transaction.contains("state = State.COMMITTED"));
        assertTrue(transaction.contains("State.ROLLBACK_FAILED"));
    }

    @Test
    void publicationMustUseVerifiedReleaseEntryPoint() throws IOException {
        Path orchestrationRoot = EDIT_SOURCE_ROOT.getParent();
        String releaseService = Files.readString(orchestrationRoot.resolve(Path.of(
                "workspace", "GenerationWorkspaceReleaseService.java")));
        String heavyCoordinator = Files.readString(orchestrationRoot.resolve(Path.of(
                "heavy", "HeavyGenerationCoordinator.java")));
        String pipelineExecutor = Files.readString(orchestrationRoot.resolve(Path.of(
                "pipeline", "GenerationPipelineExecutor.java")));

        assertTrue(releaseService.contains("releaseVerified("));
        assertTrue(releaseService.contains("return releaseVerified(session, targetType)"));
        assertTrue(heavyCoordinator.contains("workspaceReleaseService.releaseVerified("));
        assertTrue(pipelineExecutor.contains("workspaceReleaseService.releaseVerified("));
        assertFalse(heavyCoordinator.contains("workspaceReleaseService.release("));
        assertFalse(pipelineExecutor.contains("workspaceReleaseService.release("));
    }}