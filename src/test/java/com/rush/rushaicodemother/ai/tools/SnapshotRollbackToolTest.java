package com.rush.rushaicodemother.ai.tools;

import com.rush.rushaicodemother.infrastructure.filesystem.WorkspaceFileSystemService;
import com.rush.rushaicodemother.orchestration.snapshot.SnapshotNamePolicy;
import com.rush.rushaicodemother.orchestration.tool.GenerationToolExecutionContextService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SnapshotRollbackToolTest {

    private GenerationToolExecutionContextService executionContextService;
    private ToolWorkspaceFileService workspaceFileService;
    private WorkspaceFileSystemService workspaceFileSystemService;
    private SnapshotRollbackTool tool;

    @BeforeEach
    void setUp() {
        executionContextService = mock(GenerationToolExecutionContextService.class);
        workspaceFileService = mock(ToolWorkspaceFileService.class);
        workspaceFileSystemService = mock(WorkspaceFileSystemService.class);
        tool = new SnapshotRollbackTool(
                executionContextService,
                workspaceFileService,
                workspaceFileSystemService,
                new SnapshotNamePolicy()
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
}
