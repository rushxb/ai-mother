package com.rush.rushaicodemother.ai.tools;

import com.rush.rushaicodemother.infrastructure.filesystem.WorkspaceFileSystemService;
import com.rush.rushaicodemother.orchestration.snapshot.GenerationSnapshotWorkspaceService;
import com.rush.rushaicodemother.orchestration.snapshot.SnapshotNamePolicy;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionCancelledException;
import com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskFenceGuard;
import com.rush.rushaicodemother.orchestration.tool.GenerationToolExecutionContextService;
import com.rush.rushaicodemother.orchestration.tool.GenerationToolExecutionContext;
import com.rush.rushaicodemother.orchestration.tool.DestructiveToolAction;
import com.rush.rushaicodemother.orchestration.tool.ToolApprovalService;
import com.rush.rushaicodemother.orchestration.tool.GenerationApprovalRequiredException;
import cn.hutool.crypto.digest.DigestUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
    private WorkspaceFileSystemService workspaceFileSystemService;
    private GenerationSnapshotWorkspaceService snapshotWorkspaceService;
    private ToolApprovalService toolApprovalService;
    private GenerationTaskFenceGuard fenceGuard;
    private SnapshotRollbackTool tool;

    @BeforeEach
    void setUp() {
        executionContextService = mock(GenerationToolExecutionContextService.class);
        workspaceFileService = mock(ToolWorkspaceFileService.class);
        workspaceFileSystemService = mock(WorkspaceFileSystemService.class);
        snapshotWorkspaceService = mock(GenerationSnapshotWorkspaceService.class);
        toolApprovalService = mock(ToolApprovalService.class);
        fenceGuard = mock(GenerationTaskFenceGuard.class);
        when(snapshotWorkspaceService.resolveApplicationRoot(any()))
                .thenAnswer(invocation -> {
                    Object appId = invocation.getArgument(0);
                    return Path.of("target", "test-snapshots", String.valueOf(appId));
                });
        when(snapshotWorkspaceService.resolveSnapshot(any(), anyString()))
                .thenReturn(Path.of("target", "test-snapshots", "9", "safe"));
        tool = new SnapshotRollbackTool(
                executionContextService,
                workspaceFileService,
                workspaceFileSystemService,
                snapshotWorkspaceService,
                new SnapshotNamePolicy(),
                toolApprovalService,
                fenceGuard
        );
    }

    @Test
    void shouldListEmptySnapshotsWithoutRequiringProjectDirectory() throws Exception {
        when(workspaceFileSystemService.isDirectory(any(Path.class))).thenReturn(false);

        String result = tool.manageSnapshot("listSnapshots", null, null, 9L);

        assertEquals("当前没有可用快照", result);
        verify(workspaceFileService, never()).resolveDirectory(any(), anyString());
        verify(workspaceFileSystemService, never()).ensureDirectory(any(Path.class));
    }

    @Test
    void shouldRejectInvalidApplicationIdBeforeCreatingSnapshotDirectory() throws Exception {
        String result = tool.manageSnapshot("createSnapshot", "safe", null, null);

        assertEquals("错误：应用标识不能为空且必须为正数", result);
        verify(workspaceFileSystemService, never()).ensureDirectory(any(Path.class));
    }

    @Test
    void shouldRejectPathLikeSnapshotNameBeforeDeleting() throws Exception {
        String result = tool.manageSnapshot("deleteSnapshot", "../other-app", null, 9L);

        assertEquals("错误：快照名称只能包含字母、数字、下划线和短横线", result);
        verify(workspaceFileSystemService, never()).deleteDirectory(any(Path.class));
    }

    @Test
    void destructiveSnapshotActionMustRequireOneTimeTaskApproval() throws Exception {
        when(workspaceFileSystemService.isDirectory(any(Path.class))).thenReturn(true);
        when(executionContextService.currentInvocation()).thenReturn(Optional.empty());
        when(executionContextService.getContext(9L)).thenReturn(Optional.of(
                new GenerationToolExecutionContext(9L, "task-approval", "agent_edit", null,
                        null, false, "test")));

        GenerationApprovalRequiredException required = assertThrows(
                GenerationApprovalRequiredException.class,
                () -> tool.manageSnapshot("deleteSnapshot", "safe", null, 9L));

        String approvalId = DigestUtil.sha256Hex(
                "9:SNAPSHOT_DELETE:safe:"
        );
        assertEquals(approvalId, required.approvalId());
        assertEquals("task-approval", required.taskId());
        verify(toolApprovalService).isExecutionAuthorized(
                "task-approval", DestructiveToolAction.SNAPSHOT_DELETE, approvalId, null);
        verify(toolApprovalService, never()).requestApproval(
                anyString(), any(), anyString(), org.mockito.ArgumentMatchers.anyMap());
        verify(workspaceFileSystemService, never()).deleteDirectory(any(Path.class));
    }

    @Test
    void retriedApprovedDeleteMustTreatAlreadyMissingSnapshotAsIdempotentSuccess() throws Exception {
        GenerationToolExecutionContextService.ToolInvocationExecution invocation =
                new GenerationToolExecutionContextService.ToolInvocationExecution(
                        "task-approval", "call-1", "manageSnapshot", "a".repeat(64));
        when(executionContextService.getContext(9L)).thenReturn(Optional.of(
                new GenerationToolExecutionContext(9L, "task-approval", "agent_edit", null,
                        null, false, "test")));
        when(executionContextService.currentInvocation()).thenReturn(Optional.of(invocation));
        when(workspaceFileSystemService.isDirectory(any(Path.class))).thenReturn(false);
        when(toolApprovalService.isExecutionAuthorized(
                org.mockito.ArgumentMatchers.eq("task-approval"),
                org.mockito.ArgumentMatchers.eq(DestructiveToolAction.SNAPSHOT_DELETE),
                anyString(), org.mockito.ArgumentMatchers.eq(invocation))).thenReturn(true);

        String result = tool.manageSnapshot("deleteSnapshot", "safe", null, 9L);

        assertEquals("快照已由同一审批调用删除: safe", result);
        verify(fenceGuard).assertCurrent("task-approval");
        verify(workspaceFileSystemService, never()).deleteDirectory(any(Path.class));
    }

    @Test
    void staleTaskCancellationMustStopSnapshotMutationInsteadOfBecomingToolFeedback() throws Exception {
        GenerationExecutionCancelledException cancellation =
                new GenerationExecutionCancelledException("lease_lost");
        when(executionContextService.getContext(9L)).thenReturn(Optional.of(
                new GenerationToolExecutionContext(9L, "task-stale", "agent_edit", null,
                        null, false, "test")));
        doThrow(cancellation).when(fenceGuard).assertCurrent("task-stale");

        GenerationExecutionCancelledException thrown = assertThrows(
                GenerationExecutionCancelledException.class,
                () -> tool.manageSnapshot("createSnapshot", "safe", null, 9L)
        );

        assertSame(cancellation, thrown);
        verify(workspaceFileSystemService, never()).copyDirectory(any(Path.class), any(Path.class));
    }
}
