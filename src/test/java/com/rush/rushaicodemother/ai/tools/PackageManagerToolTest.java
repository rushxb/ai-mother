package com.rush.rushaicodemother.ai.tools;

import cn.hutool.core.io.FileUtil;
import com.rush.rushaicodemother.ai.tools.policy.DependencyPolicyService;
import com.rush.rushaicodemother.constant.AppConstant;
import com.rush.rushaicodemother.orchestration.artifact.PatchApplyResult;
import com.rush.rushaicodemother.orchestration.patch.PatchOperation;
import com.rush.rushaicodemother.orchestration.tool.ToolExecutionGateway;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PackageManagerToolTest {

    private static final Path TEST_OUTPUT_ROOT = Path.of(AppConstant.CODE_OUTPUT_ROOT_DIR).toAbsolutePath().normalize();

    @Test
    void managePackageJsonShouldRejectDangerousScript() throws Exception {
        PackageManagerTool tool = createTool(1L);

        String result = tool.managePackageJson(
                "setScript", null, null, null, "clean", "rm -rf /", false, null, 1L
        );

        assertTrue(result.contains("依赖策略拒绝"));
    }

    @Test
    void managePackageJsonShouldRejectDependencyWithoutReason() throws Exception {
        PackageManagerTool tool = createTool(2L);

        String result = tool.managePackageJson(
                "addDependency", "marked", "^12.0.0", "dependencies", null, null, false, "", 2L
        );

        assertTrue(result.contains("reason"));
    }

    @Test
    void managePackageJsonShouldRecordApprovedDependencyReason() throws Exception {
        PackageManagerTool tool = createTool(3L);

        String result = tool.managePackageJson(
                "addDependency", "marked", "^12.0.0", "dependencies", null, null, false, "渲染 markdown", 3L
        );

        assertTrue(result.contains("已添加依赖"));
        assertTrue(result.contains("package: marked"));
        assertTrue(result.contains("dependencyType: dependencies"));
        assertTrue(result.contains("reason: 渲染 markdown"));
    }

    private PackageManagerTool createTool(long appId) throws Exception {
        Path projectDir = TEST_OUTPUT_ROOT.resolve("vue_project_" + appId);
        FileUtil.del(projectDir.toFile());
        Files.createDirectories(projectDir);
        FileUtil.writeString("{\n  \"scripts\": {},\n  \"dependencies\": {}\n}",
                projectDir.resolve("package.json").toFile(), StandardCharsets.UTF_8);
        PackageManagerTool tool = new PackageManagerTool();
        ReflectionTestUtils.setField(tool, "dependencyPolicyService", new DependencyPolicyService());
        ToolExecutionGateway gateway = mock(ToolExecutionGateway.class);
        when(gateway.applyPatch(anyLong(), any(Path.class), any(PatchOperation.class), anyString(), anyString()))
                .thenReturn(PatchApplyResult.applied(appId, "test-package-json", projectDir.toString(), 1, java.util.List.of("package.json")));
        ReflectionTestUtils.setField(tool, "toolExecutionGateway", gateway);
        return tool;
    }
}
