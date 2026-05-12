package com.yupi.yuaicodemother.ai.tools;

import com.yupi.yuaicodemother.model.enums.CodeGenTypeEnum;
import com.yupi.yuaicodemother.monitor.GenerationOrchestrationMetricsCollector;
import com.yupi.yuaicodemother.orchestration.artifact.ChangePlan;
import com.yupi.yuaicodemother.orchestration.patch.GenerationPatchApplyService;
import com.yupi.yuaicodemother.orchestration.tool.GenerationToolExecutionContextService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class FileWriteToolTest {

    private static final Path TEST_OUTPUT_ROOT = Path.of("target", "test-code-output").toAbsolutePath().normalize();

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

    private FileWriteTool createTool(long appId, ChangePlan changePlan, boolean allowBootstrap) throws Exception {
        Path projectDir = TEST_OUTPUT_ROOT.resolve("vue_project_" + appId);
        cn.hutool.core.io.FileUtil.del(projectDir.toFile());
        Files.createDirectories(projectDir.resolve("src"));
        GenerationToolExecutionContextService contextService = new GenerationToolExecutionContextService();
        contextService.bindChangePlan(appId, "task-" + appId, allowBootstrap ? "full_generation" : "patch_first", CodeGenTypeEnum.VUE_PROJECT, changePlan, allowBootstrap, "test");
        return new FileWriteTool(new GenerationPatchApplyService(new GenerationOrchestrationMetricsCollector(new SimpleMeterRegistry())), contextService);
    }
}
