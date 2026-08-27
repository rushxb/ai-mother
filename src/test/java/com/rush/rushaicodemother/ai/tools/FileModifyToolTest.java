package com.rush.rushaicodemother.ai.tools;

import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.patch.PatchApplyServiceTestFactory;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContextService;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationRuntimeProperties;
import com.rush.rushaicodemother.orchestration.tool.GenerationToolExecutionContextService;
import com.rush.rushaicodemother.orchestration.tool.ToolExecutionGateway;
import com.rush.rushaicodemother.orchestration.tool.ToolPublicFailureException;
import com.rush.rushaicodemother.orchestration.tool.ToolResultEvidence;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.service.tool.ToolExecutionResult;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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

    @Test
    void replacementEvidenceMustDistinguishNoOpFromEffectiveMutation() throws Exception {
        try (ToolWorkspaceTestProject project = ToolWorkspaceTestProject.create(992_006L)) {
            Files.writeString(project.root().resolve("App.vue"), "same-content");
            FileModifyTool tool = new FileModifyTool(
                    realGateway(project.appId()), project.fileService());

            Object noOp = tool.modifyFile(
                    "App.vue", "same-content", "same-content", project.appId());
            Object changed = tool.modifyFile(
                    "App.vue", "same-content", "changed-content", project.appId());

            assertInstanceOf(TextContent.class, noOp,
                    "文件工具必须通过统一结果 carrier 持久化变更事实");
            assertTrue(effectivePaths("modify-noop", noOp).isEmpty());
            assertEquals(List.of("App.vue"), effectivePaths("modify-effective", changed));
            assertEquals("changed-content", Files.readString(project.root().resolve("App.vue")));
        }
    }

    private ToolExecutionGateway realGateway(long appId) {
        GenerationToolExecutionContextService contextService =
                new GenerationToolExecutionContextService();
        contextService.bindChangePlan(
                appId,
                "task-" + appId,
                "full_generation",
                CodeGenTypeEnum.VUE_PROJECT,
                null,
                true,
                "test"
        );
        return new ToolExecutionGateway(
                PatchApplyServiceTestFactory.create(),
                contextService,
                new GenerationExecutionContextService(new GenerationRuntimeProperties())
        );
    }

    private List<String> effectivePaths(String requestId, Object result) {
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .id(requestId)
                .name("modifyFile")
                .arguments("{\"relativeFilePath\":\"App.vue\"}")
                .build();
        Content content = result instanceof Content resultContent
                ? resultContent
                : TextContent.from(String.valueOf(result));
        ToolExecutionResult executionResult = ToolExecutionResult.builder()
                .result(result)
                .resultContents(List.of(content))
                .build();
        ToolExecutionResultMessage message = ToolResultEvidence.toMessage(request, executionResult);
        return ToolResultEvidence.effectiveMutationPaths(message);
    }
}
