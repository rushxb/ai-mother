package com.rush.rushaicodemother.ai.tools;

import com.rush.rushaicodemother.constant.AppConstant;
import com.rush.rushaicodemother.orchestration.index.WorkspaceSemanticIndexService;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        Path projectDirectory = Path.of(AppConstant.CODE_OUTPUT_ROOT_DIR, "vue_project_" + appId)
                .toAbsolutePath()
                .normalize();
        Files.createDirectories(projectDirectory);
        WorkspaceSemanticIndexService semanticIndexService = mock(WorkspaceSemanticIndexService.class);
        when(semanticIndexService.search(any(Path.class), anyString(), anySet(), anyInt()))
                .thenThrow(new IllegalStateException("provider-api-key=secret-value"));
        ProjectSearchTool tool = new ProjectSearchTool(
                semanticIndexService,
                ToolPathSupportTestFixture.workspaceForApp(appId)
        );

        String result = tool.searchProject("App", "vue", "", appId);

        assertTrue(result.contains("项目搜索失败"));
        assertFalse(result.contains("secret-value"));
    }
}
