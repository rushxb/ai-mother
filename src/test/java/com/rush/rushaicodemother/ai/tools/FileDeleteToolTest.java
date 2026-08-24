package com.rush.rushaicodemother.ai.tools;

import cn.hutool.crypto.digest.DigestUtil;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.artifact.PatchApplyResult;
import com.rush.rushaicodemother.orchestration.patch.PatchWorkspaceTarget;
import com.rush.rushaicodemother.orchestration.patch.PatchOperation;
import com.rush.rushaicodemother.orchestration.tool.DestructiveToolAction;
import com.rush.rushaicodemother.orchestration.tool.GenerationApprovalRequiredException;
import com.rush.rushaicodemother.orchestration.tool.GenerationToolExecutionContext;
import com.rush.rushaicodemother.orchestration.tool.GenerationToolExecutionContextService;
import com.rush.rushaicodemother.orchestration.tool.ToolApprovalService;
import com.rush.rushaicodemother.orchestration.tool.ToolExecutionGateway;
import com.rush.rushaicodemother.orchestration.tool.ToolPublicFailureException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FileDeleteToolTest {

    private ToolExecutionGateway gateway;
    private ToolWorkspaceFileService workspaceFileService;
    private GenerationToolExecutionContextService executionContextService;
    private ToolApprovalService approvalService;
    private FileDeleteTool tool;

    @BeforeEach
    void setUp() {
        gateway = mock(ToolExecutionGateway.class);
        workspaceFileService = mock(ToolWorkspaceFileService.class);
        executionContextService = mock(GenerationToolExecutionContextService.class);
        approvalService = mock(ToolApprovalService.class);
        tool = new FileDeleteTool(
                gateway, workspaceFileService, executionContextService, approvalService);
    }

    @Test
    void unapprovedDeletionMustSuspendBeforeAnyPatchSideEffect() {
        ToolWorkspaceFileService.ToolWorkspaceFile file = file("src/obsolete.ts");
        when(workspaceFileService.resolveFile(9L, "src/obsolete.ts")).thenReturn(file);
        when(workspaceFileService.exists(file)).thenReturn(true);
        when(workspaceFileService.isRegularFile(file)).thenReturn(true);
        when(executionContextService.getContext(9L)).thenReturn(Optional.of(context()));
        when(executionContextService.currentInvocation()).thenReturn(Optional.empty());

        GenerationApprovalRequiredException exception = assertThrows(
                GenerationApprovalRequiredException.class,
                () -> tool.deleteFile("src/obsolete.ts", 9L)
        );

        assertEquals("task-delete", exception.taskId());
        assertEquals(DestructiveToolAction.FILE_DELETE, exception.action());
        assertEquals("src/obsolete.ts", exception.requestDetails().get("relativeFilePath"));
        assertEquals(DigestUtil.sha256Hex("9:FILE_DELETE:src/obsolete.ts"), exception.approvalId());
        verify(gateway, never()).applyPatch(
                any(), any(), any(PatchOperation.class), any(), any());
    }

    @Test
    void exactApprovedInvocationMayDeleteOnceThroughPatchGateway() {
        ToolWorkspaceFileService.ToolWorkspaceFile file = file("src/obsolete.ts");
        GenerationToolExecutionContextService.ToolInvocationExecution invocation =
                new GenerationToolExecutionContextService.ToolInvocationExecution(
                        "task-delete", "call-1", "deleteFile", "args-hash");
        String approvalId = DigestUtil.sha256Hex("9:FILE_DELETE:src/obsolete.ts");
        when(workspaceFileService.resolveFile(9L, "src/obsolete.ts")).thenReturn(file);
        when(workspaceFileService.exists(file)).thenReturn(true);
        when(workspaceFileService.isRegularFile(file)).thenReturn(true);
        when(executionContextService.getContext(9L)).thenReturn(Optional.of(context()));
        when(executionContextService.currentInvocation()).thenReturn(Optional.of(invocation));
        when(approvalService.isExecutionAuthorized(
                "task-delete", DestructiveToolAction.FILE_DELETE, approvalId, invocation))
                .thenReturn(true);
        when(gateway.applyPatch(
                eq(9L), eq(file.projectRoot()), any(PatchOperation.class),
                eq("tool-delete-file"), eq("delete_file")))
                .thenReturn(PatchApplyResult.applied(
                        9L, "task-delete", file.projectRoot().toString(), 1,
                        List.of("src/obsolete.ts")));

        String result = tool.deleteFile("src/obsolete.ts", 9L);

        assertEquals("文件删除成功: src/obsolete.ts", result);
        verify(gateway).applyPatch(
                eq(9L), eq(file.projectRoot()), any(PatchOperation.class),
                eq("tool-delete-file"), eq("delete_file"));
    }

    @Test
    void protectedFileRejectionMustBeAProtocolFailure() {
        ToolWorkspaceFileService.ToolWorkspaceFile file = file("src/App.vue");
        when(workspaceFileService.resolveFile(9L, "src/App.vue")).thenReturn(file);
        when(workspaceFileService.exists(file)).thenReturn(true);
        when(workspaceFileService.isRegularFile(file)).thenReturn(true);

        ToolPublicFailureException failure = assertThrows(
                ToolPublicFailureException.class,
                () -> tool.deleteFile("src/App.vue", 9L));

        assertEquals("错误：不允许删除重要文件 - App.vue", failure.publicMessage());
        verify(gateway, never()).applyPatch(
                any(), any(), any(PatchOperation.class), any(), any());
    }

    @Test
    void toolMustDeclareDestructiveRisk() {
        assertEquals(ToolRiskLevel.DESTRUCTIVE, tool.getRiskLevel());
    }

    private GenerationToolExecutionContext context() {
        return new GenerationToolExecutionContext(
                9L, "task-delete", "agent_edit", CodeGenTypeEnum.VUE_PROJECT,
                null, false, "test");
    }

    private ToolWorkspaceFileService.ToolWorkspaceFile file(String relativePath) {
        Path root = Path.of("target", "file-delete-tool-test").toAbsolutePath().normalize();
        return new ToolWorkspaceFileService.ToolWorkspaceFile(
                relativePath,
                new PatchWorkspaceTarget(root, relativePath, root.resolve(relativePath).normalize())
        );
    }
}
