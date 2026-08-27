package com.rush.rushaicodemother.ai.tools;

import cn.hutool.crypto.digest.DigestUtil;
import com.rush.rushaicodemother.infrastructure.filesystem.WorkspaceDirectoryFingerprint;
import com.rush.rushaicodemother.infrastructure.filesystem.WorkspaceFileSystemException;
import com.rush.rushaicodemother.infrastructure.filesystem.WorkspaceFileSystemService;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionCancelledException;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionFence;
import com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskFenceGuard;
import com.rush.rushaicodemother.orchestration.patch.PatchWorkspaceTarget;
import com.rush.rushaicodemother.orchestration.snapshot.GenerationSnapshotWorkspaceService;
import com.rush.rushaicodemother.orchestration.snapshot.SnapshotCapture;
import com.rush.rushaicodemother.orchestration.snapshot.SnapshotKind;
import com.rush.rushaicodemother.orchestration.snapshot.SnapshotNamePolicy;
import com.rush.rushaicodemother.orchestration.snapshot.SnapshotScope;
import com.rush.rushaicodemother.orchestration.snapshot.SnapshotSelector;
import com.rush.rushaicodemother.orchestration.snapshot.SnapshotStoreException;
import com.rush.rushaicodemother.orchestration.snapshot.StoredSnapshot;
import com.rush.rushaicodemother.orchestration.tool.DestructiveToolAction;
import com.rush.rushaicodemother.orchestration.tool.GenerationApprovalRequiredException;
import com.rush.rushaicodemother.orchestration.tool.GenerationToolExecutionContext;
import com.rush.rushaicodemother.orchestration.tool.GenerationToolExecutionContextService;
import com.rush.rushaicodemother.orchestration.tool.ToolApprovalService;
import com.rush.rushaicodemother.orchestration.tool.ToolPublicFailureException;
import com.rush.rushaicodemother.orchestration.tool.ToolResultEvidence;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.service.tool.ToolExecutionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SnapshotRollbackToolTest {

    private GenerationToolExecutionContextService executionContextService;
    private ToolWorkspaceFileService workspaceFileService;
    private GenerationSnapshotWorkspaceService snapshotWorkspaceService;
    private ToolApprovalService toolApprovalService;
    private GenerationTaskFenceGuard fenceGuard;
    private SnapshotRollbackTool tool;

    @BeforeEach
    void setUp() {
        executionContextService = mock(GenerationToolExecutionContextService.class);
        workspaceFileService = mock(ToolWorkspaceFileService.class);
        snapshotWorkspaceService = mock(GenerationSnapshotWorkspaceService.class);
        toolApprovalService = mock(ToolApprovalService.class);
        fenceGuard = mock(GenerationTaskFenceGuard.class);
        tool = new SnapshotRollbackTool(
                executionContextService,
                workspaceFileService,
                snapshotWorkspaceService,
                new SnapshotNamePolicy(),
                toolApprovalService,
                fenceGuard
        );
    }

    @Test
    void shouldListOnlyVerifiedSnapshotFacts() throws Exception {
        StoredSnapshot snapshot = snapshot("safe", "11111111-1111-1111-1111-111111111111", SnapshotKind.MANUAL);
        when(snapshotWorkspaceService.listSnapshots(9L)).thenReturn(List.of(snapshot));

        TextContent result = tool.manageSnapshot("listSnapshots", null, null, 9L);

        assertTrue(result.text().contains("safe"));
        assertTrue(result.text().contains(snapshot.snapshotId()));
        assertTrue(result.text().contains("vue_project"));
        assertFalse(ToolResultEvidence.confirmsWorkspaceInvalidation(durableMessage("listSnapshots", result)));
        verify(workspaceFileService, never()).resolveDirectory(any(), anyString());
    }

    @Test
    void shouldRejectInvalidApplicationIdBeforeAnySnapshotMutation() throws Exception {
        ToolPublicFailureException failure = assertThrows(
                ToolPublicFailureException.class,
                () -> tool.manageSnapshot("createSnapshot", "safe", null, null)
        );

        assertEquals("错误：应用标识不能为空且必须为正数", failure.publicMessage());
        verify(snapshotWorkspaceService, never()).capture(any(), any());
    }

    @Test
    void createSnapshotMustPersistScopeTypeAndExecutionEpoch() throws Exception {
        bindContext("task-create", 7L);
        Path projectRoot = Path.of("target", "test-projects", "9", "frontend");
        Path workspaceRoot = Path.of("target", "test-projects", "9");
        when(workspaceFileService.resolveDirectory(9L, "frontend")).thenReturn(
                new ToolWorkspaceFileService.ToolWorkspaceDirectory(
                        "frontend",
                        workspaceRoot,
                        new PatchWorkspaceTarget(workspaceRoot, "frontend", projectRoot)
                ));
        StoredSnapshot snapshot = new StoredSnapshot(
                "safe",
                "11111111-1111-1111-1111-111111111111",
                new SnapshotScope(9L, CodeGenTypeEnum.VUE_PROJECT, "frontend"),
                SnapshotKind.MANUAL,
                "task-create",
                7L,
                Path.of("target", "test-snapshots", "9", "11111111-1111-1111-1111-111111111111"),
                Path.of("target", "test-snapshots", "9", "11111111-1111-1111-1111-111111111111", "payload"),
                new WorkspaceDirectoryFingerprint(1, 2, "a".repeat(64)),
                "b".repeat(64),
                Instant.parse("2026-08-27T10:00:00Z")
        );
        when(snapshotWorkspaceService.capture(any(SnapshotCapture.class), any(Runnable.class)))
                .thenReturn(snapshot);

        TextContent result = tool.manageSnapshot("createSnapshot", "safe", "frontend", 9L);

        assertTrue(result.text().contains(snapshot.snapshotId()));
        org.mockito.ArgumentCaptor<SnapshotCapture> capture = org.mockito.ArgumentCaptor.forClass(SnapshotCapture.class);
        verify(snapshotWorkspaceService).capture(capture.capture(), any(Runnable.class));
        assertEquals("frontend", capture.getValue().scope().relativePath());
        assertEquals(CodeGenTypeEnum.VUE_PROJECT, capture.getValue().scope().workspaceType());
        assertEquals(7L, capture.getValue().creatorExecutionEpoch());
    }

    @Test
    void destructiveApprovalMustBindImmutableSnapshotIdentity() throws Exception {
        bindContext("task-approval", 3L);
        StoredSnapshot snapshot = snapshot("safe", "11111111-1111-1111-1111-111111111111", SnapshotKind.MANUAL);
        when(snapshotWorkspaceService.requireSnapshot(any(SnapshotSelector.class))).thenReturn(snapshot);
        when(executionContextService.currentInvocation()).thenReturn(Optional.empty());

        GenerationApprovalRequiredException required = assertThrows(
                GenerationApprovalRequiredException.class,
                () -> tool.manageSnapshot("deleteSnapshot", "safe", null, 9L)
        );

        String approvalId = DigestUtil.sha256Hex(
                "9:SNAPSHOT_DELETE:" + snapshot.snapshotId() + ":" + snapshot.manifestSha256()
                        + ":vue_project:."
        );
        assertEquals(approvalId, required.approvalId());
        assertEquals(snapshot.snapshotId(), required.requestDetails().get("snapshotId"));
        verify(toolApprovalService).isExecutionAuthorized(
                "task-approval", DestructiveToolAction.SNAPSHOT_DELETE, approvalId, null);
        verify(snapshotWorkspaceService, never()).deleteSnapshot(any());
    }

    @Test
    void approvedRollbackMustUseExactSelectorAndEmitWorkspaceInvalidation() throws Exception {
        bindContext("task-approval", 3L);
        GenerationToolExecutionContextService.ToolInvocationExecution invocation = invocation("task-approval");
        when(executionContextService.currentInvocation()).thenReturn(Optional.of(invocation));
        when(toolApprovalService.isExecutionAuthorized(
                org.mockito.ArgumentMatchers.eq("task-approval"),
                org.mockito.ArgumentMatchers.eq(DestructiveToolAction.SNAPSHOT_ROLLBACK),
                anyString(),
                org.mockito.ArgumentMatchers.eq(invocation))).thenReturn(true);
        Path projectRoot = Path.of("target", "test-projects", "9");
        when(workspaceFileService.resolveDirectory(9L, null)).thenReturn(
                new ToolWorkspaceFileService.ToolWorkspaceDirectory("", projectRoot, null));
        StoredSnapshot source = snapshot("safe", "11111111-1111-1111-1111-111111111111", SnapshotKind.MANUAL);
        StoredSnapshot backup = snapshot(
                "pre_rollback_backup",
                "22222222-2222-2222-2222-222222222222",
                SnapshotKind.PRE_ROLLBACK_BACKUP
        );
        when(snapshotWorkspaceService.requireSnapshot(any(SnapshotSelector.class))).thenReturn(source);
        when(snapshotWorkspaceService.captureOrReuse(any(SnapshotCapture.class), any(Runnable.class)))
                .thenReturn(backup);
        when(snapshotWorkspaceService.restore(any(SnapshotSelector.class),
                org.mockito.ArgumentMatchers.eq(projectRoot), any(Runnable.class)))
                .thenReturn(new WorkspaceFileSystemService.WorkspaceCopyResult(projectRoot, 1, 2, "a".repeat(64)));

        TextContent result = tool.manageSnapshot("rollbackSnapshot", "safe", null, 9L);

        assertTrue(ToolResultEvidence.confirmsWorkspaceInvalidation(durableMessage("rollbackSnapshot", result)));
        org.mockito.ArgumentCaptor<SnapshotSelector> selector = org.mockito.ArgumentCaptor.forClass(SnapshotSelector.class);
        verify(snapshotWorkspaceService).restore(selector.capture(),
                org.mockito.ArgumentMatchers.eq(projectRoot), any(Runnable.class));
        assertEquals(source.snapshotId(), selector.getValue().expectedSnapshotId());
        assertEquals(source.manifestSha256(), selector.getValue().expectedManifestSha256());
        verify(fenceGuard, org.mockito.Mockito.atLeast(2)).assertCurrent("task-approval");
    }

    @Test
    void unknownRollbackOutcomeMustRequireManualReconciliation() throws Exception {
        bindContext("task-approval", 3L);
        GenerationToolExecutionContextService.ToolInvocationExecution invocation = invocation("task-approval");
        when(executionContextService.currentInvocation()).thenReturn(Optional.of(invocation));
        when(toolApprovalService.isExecutionAuthorized(anyString(), any(), anyString(), any())).thenReturn(true);
        when(workspaceFileService.resolveDirectory(9L, null)).thenReturn(
                new ToolWorkspaceFileService.ToolWorkspaceDirectory("", Path.of("target/project"), null));
        when(snapshotWorkspaceService.requireSnapshot(any())).thenReturn(
                snapshot("safe", "11111111-1111-1111-1111-111111111111", SnapshotKind.MANUAL));
        when(snapshotWorkspaceService.captureOrReuse(any(), any())).thenReturn(
                snapshot("backup", "22222222-2222-2222-2222-222222222222", SnapshotKind.PRE_ROLLBACK_BACKUP));
        when(snapshotWorkspaceService.restore(any(), any(), any())).thenThrow(new WorkspaceFileSystemException(
                WorkspaceFileSystemException.Reason.REPLACE_OUTCOME_UNKNOWN,
                "physical outcome unknown"
        ));

        TextContent result = tool.manageSnapshot("rollbackSnapshot", "safe", null, 9L);

        assertTrue(result.text().contains("回滚结果无法确认"));
        assertTrue(result.text().contains("人工核对"));
    }

    @Test
    void staleTaskCancellationMustStopSnapshotMutation() throws Exception {
        bindContext("task-stale", 4L);
        GenerationExecutionCancelledException cancellation =
                new GenerationExecutionCancelledException("lease_lost");
        doThrow(cancellation).when(fenceGuard).assertCurrent("task-stale");

        GenerationExecutionCancelledException thrown = assertThrows(
                GenerationExecutionCancelledException.class,
                () -> tool.manageSnapshot("createSnapshot", "safe", null, 9L)
        );

        assertSame(cancellation, thrown);
        verify(snapshotWorkspaceService, never()).capture(any(), any());
    }

    @Test
    void missingSnapshotMustFailClosedBeforeApproval() throws Exception {
        bindContext("task-approval", 3L);
        when(snapshotWorkspaceService.requireSnapshot(any())).thenThrow(new SnapshotStoreException(
                SnapshotStoreException.Reason.NOT_FOUND,
                "missing"
        ));

        ToolPublicFailureException failure = assertThrows(
                ToolPublicFailureException.class,
                () -> tool.manageSnapshot("deleteSnapshot", "safe", null, 9L)
        );

        assertEquals("错误：快照不存在 - safe", failure.publicMessage());
        verify(toolApprovalService, never()).isExecutionAuthorized(anyString(), any(), anyString(), any());
    }

    private void bindContext(String taskId, long epoch) {
        when(executionContextService.getContext(9L)).thenReturn(Optional.of(
                new GenerationToolExecutionContext(
                        9L,
                        taskId,
                        "agent_edit",
                        CodeGenTypeEnum.VUE_PROJECT,
                        null,
                        false,
                        "test",
                        null,
                        new GenerationExecutionFence(taskId, "worker", epoch)
                )
        ));
    }

    private StoredSnapshot snapshot(String name, String id, SnapshotKind kind) {
        Path container = Path.of("target", "test-snapshots", "9", id);
        return new StoredSnapshot(
                name,
                id,
                new SnapshotScope(9L, CodeGenTypeEnum.VUE_PROJECT, "."),
                kind,
                "task-approval",
                3L,
                container,
                container.resolve("payload"),
                new WorkspaceDirectoryFingerprint(1, 2, "a".repeat(64)),
                "b".repeat(64),
                Instant.parse("2026-08-27T10:00:00Z")
        );
    }

    private GenerationToolExecutionContextService.ToolInvocationExecution invocation(String taskId) {
        return new GenerationToolExecutionContextService.ToolInvocationExecution(
                taskId,
                "call-rollback",
                "manageSnapshot",
                "c".repeat(64)
        );
    }

    private ToolExecutionResultMessage durableMessage(String action, TextContent result) {
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .id("snapshot-result")
                .name("manageSnapshot")
                .arguments("{\"action\":\"" + action + "\"}")
                .build();
        ToolExecutionResult executionResult = ToolExecutionResult.builder()
                .result(result)
                .resultContents(List.of(result))
                .build();
        return ToolResultEvidence.toMessage(request, executionResult);
    }
}
