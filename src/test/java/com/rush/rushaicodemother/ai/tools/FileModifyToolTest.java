package com.rush.rushaicodemother.ai.tools;

import com.rush.rushaicodemother.orchestration.tool.ToolExecutionGateway;
import com.rush.rushaicodemother.orchestration.tool.ToolPublicFailureException;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class FileModifyToolTest {

    @Test
    void nullOrEmptyOldContentMustBeRejectedWithoutNullPointerException() throws Exception {
        try (ToolWorkspaceTestProject project = ToolWorkspaceTestProject.create(992_003L)) {
            FileModifyTool tool = new FileModifyTool(mock(ToolExecutionGateway.class), project.fileService());

            ToolPublicFailureException nullFailure = assertThrows(
                    ToolPublicFailureException.class,
                    () -> tool.modifyFile("App.vue", null, "new", project.appId()));
            ToolPublicFailureException emptyFailure = assertThrows(
                    ToolPublicFailureException.class,
                    () -> tool.modifyFile("App.vue", "", "new", project.appId()));

            assertTrue(nullFailure.publicMessage().contains("旧内容不能为空"));
            assertTrue(emptyFailure.publicMessage().contains("旧内容不能为空"));
        }
    }

    @Test
    void nullNewContentMustBeRejectedExplicitly() throws Exception {
        try (ToolWorkspaceTestProject project = ToolWorkspaceTestProject.create(992_004L)) {
            FileModifyTool tool = new FileModifyTool(mock(ToolExecutionGateway.class), project.fileService());

            ToolPublicFailureException failure = assertThrows(
                    ToolPublicFailureException.class,
                    () -> tool.modifyFile("App.vue", "old", null, project.appId()));

            assertTrue(failure.publicMessage().contains("不能为 null"));
        }
    }

    @Test
    void missingReplacementSourceMustBeAProtocolFailureInsteadOfTextualSuccess() throws Exception {
        try (ToolWorkspaceTestProject project = ToolWorkspaceTestProject.create(992_005L)) {
            Files.writeString(project.root().resolve("App.vue"), "<template>current</template>");
            FileModifyTool tool = new FileModifyTool(mock(ToolExecutionGateway.class), project.fileService());

            ToolPublicFailureException failure = assertThrows(
                    ToolPublicFailureException.class,
                    () -> tool.modifyFile(
                            "App.vue", "missing source", "replacement", project.appId()));

            assertTrue(failure.publicMessage().contains("未找到要替换的内容"));
            assertTrue(failure.publicMessage().contains("文件未修改"));
        }
    }
}
