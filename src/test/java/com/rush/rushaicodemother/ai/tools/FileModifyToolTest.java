package com.rush.rushaicodemother.ai.tools;

import com.rush.rushaicodemother.orchestration.tool.ToolExecutionGateway;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class FileModifyToolTest {

    @Test
    void nullOrEmptyOldContentMustBeRejectedWithoutNullPointerException() throws Exception {
        try (ToolWorkspaceTestProject project = ToolWorkspaceTestProject.create(992_003L)) {
            FileModifyTool tool = new FileModifyTool(mock(ToolExecutionGateway.class), project.fileService());

            String nullResult = tool.modifyFile("App.vue", null, "new", project.appId());
            String emptyResult = tool.modifyFile("App.vue", "", "new", project.appId());

            assertTrue(nullResult.contains("旧内容不能为空"));
            assertTrue(emptyResult.contains("旧内容不能为空"));
        }
    }

    @Test
    void nullNewContentMustBeRejectedExplicitly() throws Exception {
        try (ToolWorkspaceTestProject project = ToolWorkspaceTestProject.create(992_004L)) {
            FileModifyTool tool = new FileModifyTool(mock(ToolExecutionGateway.class), project.fileService());

            String result = tool.modifyFile("App.vue", "old", null, project.appId());

            assertTrue(result.contains("不能为 null"));
        }
    }
}
