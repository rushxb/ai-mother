package com.rush.rushaicodemother.ai.tools;

import com.rush.rushaicodemother.infrastructure.filesystem.WorkspaceFileSystemService;
import com.rush.rushaicodemother.infrastructure.filesystem.WorkspaceDirectoryFingerprint;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.artifact.DiffSummary;
import com.rush.rushaicodemother.orchestration.snapshot.GenerationDiffSummaryService;
import com.rush.rushaicodemother.orchestration.snapshot.GenerationSnapshotWorkspaceService;
import com.rush.rushaicodemother.orchestration.snapshot.SnapshotNamePolicy;
import com.rush.rushaicodemother.orchestration.snapshot.SnapshotKind;
import com.rush.rushaicodemother.orchestration.snapshot.SnapshotScope;
import com.rush.rushaicodemother.orchestration.snapshot.SnapshotSelector;
import com.rush.rushaicodemother.orchestration.snapshot.StoredSnapshot;
import com.rush.rushaicodemother.orchestration.tool.GenerationToolExecutionContext;
import com.rush.rushaicodemother.orchestration.tool.GenerationToolExecutionContextService;
import com.rush.rushaicodemother.orchestration.tool.ToolPublicFailureException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DiffSummaryToolTest {

    private static final long APP_ID = 7L;

    @TempDir
    Path tempDirectory;

    private GenerationDiffSummaryService diffSummaryService;
    private ToolWorkspaceFileService workspaceFileService;
    private WorkspaceFileSystemService workspaceFileSystemService;
    private GenerationSnapshotWorkspaceService snapshotWorkspaceService;
    private GenerationToolExecutionContextService executionContextService;
    private DiffSummaryTool tool;
    private Path projectPath;

    @BeforeEach
    void setUp() throws Exception {
        diffSummaryService = mock(GenerationDiffSummaryService.class);
        workspaceFileService = mock(ToolWorkspaceFileService.class);
        workspaceFileSystemService = mock(WorkspaceFileSystemService.class);
        snapshotWorkspaceService = mock(GenerationSnapshotWorkspaceService.class);
        executionContextService = mock(GenerationToolExecutionContextService.class);
        tool = new DiffSummaryTool(
                diffSummaryService,
                workspaceFileService,
                workspaceFileSystemService,
                snapshotWorkspaceService,
                new SnapshotNamePolicy(),
                executionContextService
        );
        projectPath = tempDirectory.resolve("project");
        when(executionContextService.getContext(APP_ID)).thenReturn(Optional.of(
                new GenerationToolExecutionContext(
                        APP_ID, "task-7", "agent_edit", CodeGenTypeEnum.VUE_PROJECT,
                        null, false, "test"
                )
        ));
        when(snapshotWorkspaceService.requireSnapshot(any(SnapshotSelector.class)))
                .thenAnswer(invocation -> snapshot(
                        invocation.getArgument(0, SnapshotSelector.class).snapshotName(),
                        "."
                ));
        when(workspaceFileService.resolveDirectory(APP_ID, "src"))
                .thenReturn(new ToolWorkspaceFileService.ToolWorkspaceDirectory("", projectPath, null));
        when(workspaceFileSystemService.isDirectory(any(Path.class))).thenReturn(true);
        DiffSummary summary = DiffSummary.created(
                APP_ID,
                "",
                "base",
                "current",
                List.of("added.ts"),
                List.of(),
                List.of(),
                List.of()
        );
        when(diffSummaryService.summarizePaths(eq(null), eq(""), any(Path.class), any(Path.class)))
                .thenReturn(summary);
        when(diffSummaryService.renderText(summary)).thenReturn("生成后差异摘要\n新增文件: 1");
    }

    @Test
    void shouldSelectLatestSnapshotFromBoundedDirectoryListing() throws Exception {
        when(snapshotWorkspaceService.listSnapshots(APP_ID)).thenReturn(List.of(
                snapshot("latest_snapshot", "src"),
                snapshot("older_snapshot", "src")
        ));

        String result = tool.summarizeDiff("compareLatestSnapshot", null, null, "src", APP_ID);

        assertTrue(result.startsWith("差异对比: latest_snapshot -> current"));
        ArgumentCaptor<Path> basePath = ArgumentCaptor.forClass(Path.class);
        verify(diffSummaryService).summarizePaths(eq(null), eq(""), basePath.capture(), eq(projectPath));
        assertEquals("payload", basePath.getValue().getFileName().toString());
        assertTrue(basePath.getValue().toString().contains("latest_snapshot"));
    }

    @Test
    void shouldCompareTwoValidatedSnapshotNamesWithoutResolvingProjectDirectory() throws Exception {
        String result = tool.summarizeDiff(
                "compareSnapshots",
                "snapshot_1",
                "snapshot-2",
                null,
                APP_ID
        );

        assertTrue(result.startsWith("差异对比: snapshot_1 -> snapshot-2"));
        verify(workspaceFileService, never()).resolveDirectory(any(), anyString());
    }

    @Test
    void shouldRejectSnapshotPathTraversalBeforeFileSystemAccess() throws Exception {
        ToolPublicFailureException failure = assertThrows(
                ToolPublicFailureException.class,
                () -> tool.summarizeDiff(
                        "compareSnapshots",
                        "../another-app",
                        "snapshot_2",
                        null,
                        APP_ID
                )
        );

        assertEquals("错误：快照名称只能包含字母、数字、下划线和短横线", failure.publicMessage());
        verify(diffSummaryService, never()).summarizePaths(any(), anyString(), any(Path.class), any(Path.class));
    }

    @Test
    void shouldRejectMissingApplicationId() {
        ToolPublicFailureException failure = assertThrows(
                ToolPublicFailureException.class,
                () -> tool.summarizeDiff("compareLatestSnapshot", null, null, null, null)
        );

        assertEquals("错误：应用标识不能为空且必须为正数", failure.publicMessage());
    }

    @Test
    void missingLatestSnapshotMustBeReportedAsProtocolFailure() throws Exception {
        when(snapshotWorkspaceService.listSnapshots(APP_ID)).thenReturn(List.of());

        ToolPublicFailureException failure = assertThrows(
                ToolPublicFailureException.class,
                () -> tool.summarizeDiff("compareLatestSnapshot", null, null, "src", APP_ID)
        );

        assertEquals("错误：当前没有可对比的快照", failure.publicMessage());
    }

    @Test
    void emptyCreatedDiffMustRemainAValidEmptySuccess() throws Exception {
        DiffSummary emptySummary = DiffSummary.created(
                APP_ID,
                "",
                "snapshot_1",
                "snapshot_2",
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
        when(diffSummaryService.summarizePaths(eq(null), eq(""), any(Path.class), any(Path.class)))
                .thenReturn(emptySummary);
        when(diffSummaryService.renderText(emptySummary)).thenReturn("生成后差异摘要\n无文件变更");

        String result = tool.summarizeDiff(
                "compareSnapshots",
                "snapshot_1",
                "snapshot_2",
                null,
                APP_ID
        );

        assertTrue(result.contains("无文件变更"));
    }

    @Test
    void failedDiffSummaryMustNotExposeInternalReason() throws Exception {
        DiffSummary skipped = DiffSummary.skipped(
                APP_ID,
                "",
                "snapshot_1",
                "snapshot_2",
                "provider-api-key=secret-value"
        );
        when(diffSummaryService.summarizePaths(eq(null), eq(""), any(Path.class), any(Path.class)))
                .thenReturn(skipped);

        ToolPublicFailureException failure = assertThrows(
                ToolPublicFailureException.class,
                () -> tool.summarizeDiff(
                        "compareSnapshots",
                        "snapshot_1",
                        "snapshot_2",
                        null,
                        APP_ID
                )
        );

        assertFalse(failure.publicMessage().contains("secret-value"));
        assertEquals("错误：差异摘要生成失败，请稍后重试", failure.publicMessage());
    }

    private StoredSnapshot snapshot(String name, String scope) {
        String id = "latest_snapshot".equals(name)
                ? "11111111-1111-1111-1111-111111111111"
                : "22222222-2222-2222-2222-222222222222";
        Path container = tempDirectory.resolve("code_snapshot").resolve(String.valueOf(APP_ID)).resolve(name);
        return new StoredSnapshot(
                name,
                id,
                new SnapshotScope(APP_ID, CodeGenTypeEnum.VUE_PROJECT, scope),
                SnapshotKind.MANUAL,
                "task-7",
                1L,
                container,
                container.resolve("payload"),
                new WorkspaceDirectoryFingerprint(1, 1, "a".repeat(64)),
                "b".repeat(64),
                Instant.parse("2026-08-27T10:00:00Z")
        );
    }
}
