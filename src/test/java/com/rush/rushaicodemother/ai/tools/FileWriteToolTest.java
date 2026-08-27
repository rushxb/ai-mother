package com.rush.rushaicodemother.ai.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rush.rushaicodemother.config.AiToolWorkspaceProperties;
import com.rush.rushaicodemother.constant.AppConstant;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.artifact.ChangePlan;
import com.rush.rushaicodemother.orchestration.context.AgentConversationFolder;
import com.rush.rushaicodemother.orchestration.context.ToolRoundPathExtractor;
import com.rush.rushaicodemother.orchestration.patch.GenerationPatchApplyService;
import com.rush.rushaicodemother.orchestration.patch.PatchApplyServiceTestFactory;
import com.rush.rushaicodemother.orchestration.patch.PatchOperation;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContextService;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationRuntimeProperties;
import com.rush.rushaicodemother.orchestration.tool.GenerationToolExecutionContextService;
import com.rush.rushaicodemother.orchestration.tool.ToolExecutionGateway;
import com.rush.rushaicodemother.orchestration.tool.ToolPublicFailureException;
import com.rush.rushaicodemother.orchestration.tool.ToolResultEvidence;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.service.tool.ToolExecutionResult;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FileWriteToolTest {

    private static final Path TEST_OUTPUT_ROOT = Path.of(AppConstant.CODE_OUTPUT_ROOT_DIR).toAbsolutePath().normalize();

    @Test
    void writeFileShouldRejectUnplannedPath() throws Exception {
        FileWriteTool tool = createTool(1L, new ChangePlan(
                "v1", "feature_update", List.of("src/App.vue"), List.of(), List.of(), List.of(), "review_only", "manual_retry_without_snapshot"
        ), false);

        ToolPublicFailureException failure = assertThrows(
                ToolPublicFailureException.class,
                () -> tool.writeFile("src/Other.vue", "content", 1L));

        assertTrue(failure.publicMessage().contains("outside_change_plan")
                || failure.publicMessage().contains("patch_operation_validation_failed"));
    }

    @Test
    void writeFileShouldAllowPlannedAdd() throws Exception {
        FileWriteTool tool = createTool(2L, new ChangePlan(
                "v1", "feature_update", List.of("src/App.vue"), List.of(), List.of(), List.of(), "review_only", "manual_retry_without_snapshot"
        ), false);

        String result = tool.writeFile("src/App.vue", "content", 2L).text();

        assertTrue(result.contains("文件写入成功"));
    }

    @Test
    void writeFileShouldAllowBootstrapWrite() throws Exception {
        FileWriteTool tool = createTool(3L, null, true);

        String result = tool.writeFile("src/Bootstrap.vue", "content", 3L).text();

        assertTrue(result.contains("文件写入成功"));
    }

    @Test
    void unexpectedPatchFailureMustNotExposeInternalDetails() throws Exception {
        long appId = 4L;
        Path projectDir = TEST_OUTPUT_ROOT.resolve("vue_project_" + appId);
        Files.createDirectories(projectDir.resolve("src"));
        ToolExecutionGateway gateway = mock(ToolExecutionGateway.class);
        when(gateway.applyPatch(anyLong(), any(Path.class), any(PatchOperation.class), anyString(), anyString()))
                .thenThrow(new IllegalStateException("provider-api-key=secret-value"));
        FileWriteTool tool = new FileWriteTool(gateway, ToolPathSupportTestFixture.workspaceForApp(appId));

        ToolPublicFailureException failure = assertThrows(
                ToolPublicFailureException.class,
                () -> tool.writeFile("src/App.vue", "content", appId));

        assertTrue(failure.publicMessage().contains("文件写入失败"));
        assertFalse(failure.publicMessage().contains("secret-value"));
    }

    @Test
    void idempotentWriteMustNotBeFoldedAsALandedWorkspaceMutation() throws Exception {
        long appId = 5L;
        FileWriteTool tool = createTool(appId, null, true);
        Path file = TEST_OUTPUT_ROOT.resolve("vue_project_" + appId).resolve("src/App.vue");
        Files.writeString(file, "same-content");

        Object toolResult = tool.writeFile("src/App.vue", "same-content", appId);
        ToolExecutionRequest writeRequest = ToolExecutionRequest.builder()
                .id("write-noop")
                .name("writeFile")
                .arguments("{\"relativeFilePath\":\"src/App.vue\","
                        + "\"content\":\"same-content\"}")
                .build();
        ToolExecutionRequest recentRequest = ToolExecutionRequest.builder()
                .id("recent-read")
                .name("readFile")
                .arguments("{\"relativeFilePath\":\"src/Recent.vue\"}")
                .build();
        List<ChatMessage> messages = List.of(
                SystemMessage.from("系统提示"),
                UserMessage.from("确认项目状态"),
                AiMessage.from(writeRequest),
                ToolResultEvidence.toMessage(writeRequest, executionResult(toolResult)),
                AiMessage.from(recentRequest),
                ToolExecutionResultMessage.from(
                        recentRequest.id(), recentRequest.name(), "recent-content")
        );
        AgentConversationFolder folder = new AgentConversationFolder(
                new ToolRoundPathExtractor(new ObjectMapper())
        );

        AgentConversationFolder.FoldResult folded = folder.fold(messages, 1);
        String systemPrompt = ((SystemMessage) folded.messages().getFirst()).text();

        assertFalse(systemPrompt.contains("已写入或修改文件：src/App.vue"),
                "幂等写入没有改变磁盘，不能被折叠成已落盘 mutation:\n" + systemPrompt);
        assertTrue(systemPrompt.contains("已确认目标状态，无需重复执行"), systemPrompt);
        assertTrue(Files.readString(file).equals("same-content"));
    }

    @Test
    void userFacingExecutionResultMustPreserveTheActualNoOpOutcome() throws Exception {
        FileWriteTool tool = createTool(6L, null, true);
        cn.hutool.json.JSONObject arguments = new cn.hutool.json.JSONObject();
        arguments.set("relativeFilePath", "src/App.vue");

        String displayed = tool.generateToolExecutedResult(
                arguments,
                "文件内容已是目标状态，无需重复写入: src/App.vue"
        );

        assertTrue(displayed.contains("无需重复写入"), displayed);
        assertFalse(displayed.contains("内容已写入工作区"), displayed);
    }

    private ToolExecutionResult executionResult(Object toolResult) {
        Content content = toolResult instanceof Content resultContent
                ? resultContent
                : TextContent.from(String.valueOf(toolResult));
        return ToolExecutionResult.builder()
                .result(toolResult)
                .resultContents(List.of(content))
                .build();
    }

    private FileWriteTool createTool(long appId, ChangePlan changePlan, boolean allowBootstrap) throws Exception {
        Path projectDir = TEST_OUTPUT_ROOT.resolve("vue_project_" + appId);
        cn.hutool.core.io.FileUtil.del(projectDir.toFile());
        Files.createDirectories(projectDir.resolve("src"));
        GenerationToolExecutionContextService contextService = new GenerationToolExecutionContextService();
        contextService.bindChangePlan(appId, "task-" + appId, allowBootstrap ? "full_generation" : "patch_first", CodeGenTypeEnum.VUE_PROJECT, changePlan, allowBootstrap, "test");
        GenerationPatchApplyService patchApplyService =
                PatchApplyServiceTestFactory.create();
        return new FileWriteTool(
                new ToolExecutionGateway(
                        patchApplyService,
                        contextService,
                        new GenerationExecutionContextService(new GenerationRuntimeProperties())
                ),
                ToolPathSupportTestFixture.workspaceFrom(
                        ToolPathSupportTestFixture.from(contextService),
                        new AiToolWorkspaceProperties()
                )
        );
    }
}
