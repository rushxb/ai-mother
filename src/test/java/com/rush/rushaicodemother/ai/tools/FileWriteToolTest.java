package com.rush.rushaicodemother.ai.tools;

import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContextService;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationRuntimeProperties;
import com.rush.rushaicodemother.config.AiToolWorkspaceProperties;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.constant.AppConstant;
import com.rush.rushaicodemother.orchestration.artifact.ChangePlan;
import com.rush.rushaicodemother.orchestration.patch.GenerationPatchApplyService;
import com.rush.rushaicodemother.orchestration.patch.PatchOperation;
import com.rush.rushaicodemother.orchestration.patch.PatchApplyServiceTestFactory;
import com.rush.rushaicodemother.orchestration.tool.GenerationToolExecutionContextService;
import com.rush.rushaicodemother.orchestration.tool.ToolExecutionGateway;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

        String result = tool.writeFile("src/Other.vue", "content", 1L);

        assertTrue(result.contains("outside_change_plan") || result.contains("patch_operation_validation_failed"));
    }

    @Test
    void writeFileShouldAllowPlannedAdd() throws Exception {
        FileWriteTool tool = createTool(2L, new ChangePlan(
                "v1", "feature_update", List.of("src/App.vue"), List.of(), List.of(), List.of(), "review_only", "manual_retry_without_snapshot"
        ), false);

        String result = tool.writeFile("src/App.vue", "content", 2L);

        assertTrue(result.contains("文件写入成功"));
    }

    @Test
    void writeFileShouldAllowBootstrapWrite() throws Exception {
        FileWriteTool tool = createTool(3L, null, true);

        String result = tool.writeFile("src/Bootstrap.vue", "content", 3L);

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

        String result = tool.writeFile("src/App.vue", "content", appId);

        assertTrue(result.contains("文件写入失败"));
        assertFalse(result.contains("secret-value"));
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
