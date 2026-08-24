package com.rush.rushaicodemother.ai.tools;

import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionPolicyException;
import com.rush.rushaicodemother.orchestration.tool.ToolPublicFailureException;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReadMultipleFilesToolTest {

    @Test
    void nonPositiveCharacterLimitMustClampToOneCharacter() throws Exception {
        try (ToolWorkspaceTestProject project = ToolWorkspaceTestProject.create(992_005L)) {
            Files.writeString(project.root().resolve("sample.txt"), "abcdef");
            ReadMultipleFilesTool tool = new ReadMultipleFilesTool(project.fileService());

            String zeroLimitResult = tool.readMultipleFiles(List.of("sample.txt"), 0, project.appId());
            String negativeLimitResult = tool.readMultipleFiles(List.of("sample.txt"), -10, project.appId());

            assertTrue(zeroLimitResult.contains("a\n// 文件内容过长，已截断"));
            assertTrue(negativeLimitResult.contains("a\n// 文件内容过长，已截断"));
            assertFalse(zeroLimitResult.contains("abcdef"));
        }
    }

    @Test
    void emptyRequestMustBeReportedAsProtocolFailure() {
        ReadMultipleFilesTool tool = new ReadMultipleFilesTool(mock(ToolWorkspaceFileService.class));

        ToolPublicFailureException failure = assertThrows(
                ToolPublicFailureException.class,
                () -> tool.readMultipleFiles(List.of(), 100, 992_012L)
        );

        assertTrue(failure.publicMessage().contains("文件路径列表不能为空"));
    }

    @Test
    void allMissingFilesMustBeReportedAsProtocolFailure() throws Exception {
        try (ToolWorkspaceTestProject project = ToolWorkspaceTestProject.create(992_013L)) {
            ReadMultipleFilesTool tool = new ReadMultipleFilesTool(project.fileService());

            ToolPublicFailureException failure = assertThrows(
                    ToolPublicFailureException.class,
                    () -> tool.readMultipleFiles(List.of("missing-a.txt", "missing-b.txt"), 100, project.appId())
            );

            assertTrue(failure.publicMessage().contains("没有成功读取任何文件"));
        }
    }

    @Test
    void mixedBatchMustPreserveSuccessfulContentAndItemFailure() throws Exception {
        try (ToolWorkspaceTestProject project = ToolWorkspaceTestProject.create(992_014L)) {
            Files.writeString(project.root().resolve("existing.txt"), "useful-context");
            ReadMultipleFilesTool tool = new ReadMultipleFilesTool(project.fileService());

            String result = tool.readMultipleFiles(
                    List.of("existing.txt", "missing.txt"),
                    100,
                    project.appId()
            );

            assertTrue(result.contains("useful-context"));
            assertTrue(result.contains("missing.txt"));
            assertTrue(result.contains("错误：文件不存在"));
        }
    }

    @Test
    void taskControlFailureMustStopBatchAndPropagateIdentity() {
        ToolWorkspaceFileService workspaceFileService = mock(ToolWorkspaceFileService.class);
        GenerationExecutionPolicyException policyFailure =
                new GenerationExecutionPolicyException("任务租约已失效");
        when(workspaceFileService.resolveFile(992_015L, "src/App.vue")).thenThrow(policyFailure);
        ReadMultipleFilesTool tool = new ReadMultipleFilesTool(workspaceFileService);

        GenerationExecutionPolicyException propagated = assertThrows(
                GenerationExecutionPolicyException.class,
                () -> tool.readMultipleFiles(List.of("src/App.vue"), 100, 992_015L)
        );

        assertSame(policyFailure, propagated);
    }

    @Test
    void emptyFileMustCountAsASuccessfulRead() throws Exception {
        try (ToolWorkspaceTestProject project = ToolWorkspaceTestProject.create(992_018L)) {
            Files.writeString(project.root().resolve("empty.txt"), "");
            ReadMultipleFilesTool tool = new ReadMultipleFilesTool(project.fileService());

            String result = tool.readMultipleFiles(List.of("empty.txt"), 100, project.appId());

            assertTrue(result.contains("[文件] empty.txt"));
            assertFalse(result.contains("没有成功读取任何文件"));
        }
    }
}
