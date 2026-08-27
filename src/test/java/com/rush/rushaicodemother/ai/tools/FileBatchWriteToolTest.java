package com.rush.rushaicodemother.ai.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rush.rushaicodemother.config.AiToolWorkspaceProperties;
import com.rush.rushaicodemother.config.CodeStorageProperties;
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
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.service.tool.ToolExecutionResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FileBatchWriteToolTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldWriteMultipleFilesInOnePatch() throws Exception {
        long appId = 31L;
        FileBatchWriteTool tool = createTool(
                appId, null, true, new AiToolWorkspaceProperties());

        String result = tool.writeFiles(List.of(
                new FileBatchWriteTool.FileWrite("src/App.vue", "<template>app</template>"),
                new FileBatchWriteTool.FileWrite("src/main.js", "createApp()")
        ), appId).text();

        Path projectDir = projectDir(appId);
        assertTrue(result.contains("批量文件写入成功"));
        assertEquals("<template>app</template>", Files.readString(projectDir.resolve("src/App.vue")));
        assertEquals("createApp()", Files.readString(projectDir.resolve("src/main.js")));
    }

    @Test
    void oneUnplannedPathMustRejectTheWholeBatch() throws Exception {
        long appId = 32L;
        FileBatchWriteTool tool = createTool(appId, new ChangePlan(
                "v1", "feature_update", List.of("src/App.vue"), List.of(),
                List.of(), List.of(), "review_only", "manual_retry_without_snapshot"
        ), false, new AiToolWorkspaceProperties());

        ToolPublicFailureException failure = assertThrows(
                ToolPublicFailureException.class,
                () -> tool.writeFiles(List.of(
                        new FileBatchWriteTool.FileWrite("src/App.vue", "allowed"),
                        new FileBatchWriteTool.FileWrite("src/Other.vue", "not-allowed")
                ), appId));

        assertTrue(failure.publicMessage().contains("批量文件写入失败"));
        assertFalse(Files.exists(projectDir(appId).resolve("src/App.vue")));
        assertFalse(Files.exists(projectDir(appId).resolve("src/Other.vue")));
    }

    @Test
    void normalizedDuplicatePathsMustBeRejectedBeforeWriting() throws Exception {
        long appId = 33L;
        FileBatchWriteTool tool = createTool(
                appId, null, true, new AiToolWorkspaceProperties());

        ToolPublicFailureException failure = assertThrows(
                ToolPublicFailureException.class,
                () -> tool.writeFiles(List.of(
                        new FileBatchWriteTool.FileWrite("src/App.vue", "first"),
                        new FileBatchWriteTool.FileWrite("src/./App.vue", "second")
                ), appId));

        assertTrue(failure.publicMessage().contains("重复文件路径"));
        assertFalse(Files.exists(projectDir(appId).resolve("src/App.vue")));
    }

    @Test
    void configuredBatchLimitMustRejectBeforeWriting() throws Exception {
        long appId = 34L;
        AiToolWorkspaceProperties properties = new AiToolWorkspaceProperties();
        properties.setMaxBatchWriteFiles(1);
        FileBatchWriteTool tool = createTool(appId, null, true, properties);

        ToolPublicFailureException failure = assertThrows(
                ToolPublicFailureException.class,
                () -> tool.writeFiles(List.of(
                        new FileBatchWriteTool.FileWrite("src/App.vue", "app"),
                        new FileBatchWriteTool.FileWrite("src/main.js", "main")
                ), appId));

        assertTrue(failure.publicMessage().contains("单次最多写入 1 个文件"));
        assertFalse(Files.exists(projectDir(appId).resolve("src/App.vue")));
        assertFalse(Files.exists(projectDir(appId).resolve("src/main.js")));
    }

    @Test
    void unexpectedPatchFailureMustNotExposeInternalDetails() throws Exception {
        long appId = 35L;
        prepareProject(appId);
        ToolExecutionGateway gateway = mock(ToolExecutionGateway.class);
        when(gateway.applyPatch(anyLong(), any(Path.class), anyList(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("provider-api-key=secret-value"));
        AiToolWorkspaceProperties properties = new AiToolWorkspaceProperties();
        GenerationToolExecutionContextService contextService = bindContext(
                appId, null, true);
        FileBatchWriteTool tool = new FileBatchWriteTool(
                gateway,
                ToolPathSupportTestFixture.workspaceFrom(
                        ToolPathSupportTestFixture.from(contextService, storageProperties()), properties),
                properties);

        ToolPublicFailureException failure = assertThrows(
                ToolPublicFailureException.class,
                () -> tool.writeFiles(List.of(
                        new FileBatchWriteTool.FileWrite("src/App.vue", "content")
                ), appId));

        assertTrue(failure.publicMessage().contains("批量文件写入失败"));
        assertFalse(failure.publicMessage().contains("secret-value"));
    }

    @Test
    void mixedBatchMustFoldOnlyTheFilesActuallyChanged() throws Exception {
        long appId = 36L;
        FileBatchWriteTool tool = createTool(
                appId, null, true, new AiToolWorkspaceProperties());
        Path unchangedFile = projectDir(appId).resolve("src/App.vue");
        Files.writeString(unchangedFile, "same-content");

        Object toolResult = tool.writeFiles(List.of(
                new FileBatchWriteTool.FileWrite("src/App.vue", "same-content"),
                new FileBatchWriteTool.FileWrite("src/main.js", "new-content")
        ), appId);
        ToolExecutionRequest writeRequest = ToolExecutionRequest.builder()
                .id("write-mixed")
                .name("writeFiles")
                .arguments("{\"files\":["
                        + "{\"relativeFilePath\":\"src/App.vue\",\"content\":\"same-content\"},"
                        + "{\"relativeFilePath\":\"src/main.js\",\"content\":\"new-content\"}]}")
                .build();
        ToolExecutionRequest recentRequest = ToolExecutionRequest.builder()
                .id("recent-read")
                .name("readFile")
                .arguments("{\"relativeFilePath\":\"src/Recent.vue\"}")
                .build();
        List<ChatMessage> messages = List.of(
                SystemMessage.from("系统提示"),
                UserMessage.from("更新项目"),
                AiMessage.from(writeRequest),
                ToolResultEvidence.toMessage(writeRequest, executionResult(toolResult)),
                AiMessage.from(recentRequest),
                ToolExecutionResultMessage.from(
                        recentRequest.id(), recentRequest.name(), "recent-content")
        );
        AgentConversationFolder folder = new AgentConversationFolder(
                new ToolRoundPathExtractor(new ObjectMapper())
        );

        String systemPrompt = ((SystemMessage) folder.fold(messages, 1)
                .messages().getFirst()).text();

        assertTrue(systemPrompt.contains("已写入或修改文件：src/main.js"), systemPrompt);
        assertFalse(systemPrompt.contains("src/App.vue"),
                "批次中的 no-op 文件不能伪装成已落盘 mutation:\n" + systemPrompt);
        assertEquals("same-content", Files.readString(unchangedFile));
        assertEquals("new-content", Files.readString(projectDir(appId).resolve("src/main.js")));
    }

    @Test
    void toolSchemaMustExposeStructuredFileEntries() {
        FileBatchWriteTool tool = new FileBatchWriteTool(
                mock(ToolExecutionGateway.class),
                mock(ToolWorkspaceFileService.class),
                new AiToolWorkspaceProperties());

        ToolSpecification specification = ToolSpecifications.toolSpecificationsFrom(tool).getFirst();

        assertEquals("writeFiles", specification.name());
        assertNotNull(specification.parameters());
        JsonArraySchema files = assertInstanceOf(
                JsonArraySchema.class, specification.parameters().properties().get("files"));
        JsonObjectSchema file = assertInstanceOf(JsonObjectSchema.class, files.items());
        assertTrue(file.properties().containsKey("relativeFilePath"));
        assertTrue(file.properties().containsKey("content"));
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

    private FileBatchWriteTool createTool(long appId,
                                          ChangePlan changePlan,
                                          boolean allowBootstrap,
                                          AiToolWorkspaceProperties properties) throws Exception {
        prepareProject(appId);
        GenerationToolExecutionContextService contextService = bindContext(
                appId, changePlan, allowBootstrap);
        GenerationPatchApplyService patchApplyService = PatchApplyServiceTestFactory.create();
        ToolExecutionGateway gateway = new ToolExecutionGateway(
                patchApplyService,
                contextService,
                new GenerationExecutionContextService(new GenerationRuntimeProperties())
        );
        return new FileBatchWriteTool(
                gateway,
                ToolPathSupportTestFixture.workspaceFrom(
                        ToolPathSupportTestFixture.from(contextService, storageProperties()), properties),
                properties
        );
    }

    private GenerationToolExecutionContextService bindContext(long appId,
                                                              ChangePlan changePlan,
                                                              boolean allowBootstrap) {
        GenerationToolExecutionContextService contextService =
                new GenerationToolExecutionContextService();
        contextService.bindChangePlan(
                appId,
                "task-" + appId,
                allowBootstrap ? "full_generation" : "patch_first",
                CodeGenTypeEnum.VUE_PROJECT,
                changePlan,
                allowBootstrap,
                "test"
        );
        return contextService;
    }

    private void prepareProject(long appId) throws Exception {
        Files.createDirectories(projectDir(appId).resolve("src"));
    }

    private Path projectDir(long appId) {
        return tempDir.resolve("output").resolve("vue_project_" + appId);
    }

    private CodeStorageProperties storageProperties() {
        CodeStorageProperties properties = new CodeStorageProperties();
        properties.setOutputRootDir(tempDir.resolve("output"));
        properties.setDeployRootDir(tempDir.resolve("deploy"));
        properties.setSnapshotRootDir(tempDir.resolve("snapshot"));
        return properties;
    }
}
