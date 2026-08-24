package com.rush.rushaicodemother.ai.tools;

import com.rush.rushaicodemother.orchestration.index.WorkspaceSemanticIndexService;
import com.rush.rushaicodemother.orchestration.tool.ToolPublicFailureException;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProjectSearchToolTest {

    @Test
    void unexpectedSearchFailureMustNotExposeInternalDetails() throws Exception {
        long appId = 930_001L;
        try (ToolWorkspaceTestProject project = ToolWorkspaceTestProject.create(appId)) {
            WorkspaceSemanticIndexService semanticIndexService = mock(WorkspaceSemanticIndexService.class);
            when(semanticIndexService.search(any(Path.class), anyString(), anySet(), anyInt()))
                    .thenThrow(new IllegalStateException("provider-api-key=secret-value"));
            ProjectSearchTool tool = new ProjectSearchTool(semanticIndexService, project.fileService());

            ToolPublicFailureException failure = assertThrows(
                    ToolPublicFailureException.class,
                    () -> tool.searchProject("App", "vue", "", appId)
            );

            assertTrue(failure.publicMessage().contains("项目搜索失败"));
            assertFalse(failure.publicMessage().contains("secret-value"));
        }
    }

    @Test
    void blankKeywordMustBeReportedAsProtocolFailure() {
        ProjectSearchTool tool = new ProjectSearchTool(
                mock(WorkspaceSemanticIndexService.class),
                mock(ToolWorkspaceFileService.class)
        );

        ToolPublicFailureException failure = assertThrows(
                ToolPublicFailureException.class,
                () -> tool.searchProject(" ", null, null, 930_002L)
        );

        assertTrue(failure.publicMessage().contains("搜索关键词不能为空"));
    }

    @Test
    void noSearchHitMustRemainAValidEmptySuccess() throws Exception {
        long appId = 930_003L;
        try (ToolWorkspaceTestProject project = ToolWorkspaceTestProject.create(appId)) {
            WorkspaceSemanticIndexService semanticIndexService = mock(WorkspaceSemanticIndexService.class);
            when(semanticIndexService.search(any(Path.class), anyString(), anySet(), anyInt()))
                    .thenReturn(List.of());
            ProjectSearchTool tool = new ProjectSearchTool(semanticIndexService, project.fileService());

            assertEquals(
                    "未找到与关键词相关的文件或内容",
                    tool.searchProject("not-found", null, null, appId)
            );
        }
    }
}
