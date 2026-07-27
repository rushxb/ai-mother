package com.rush.rushaicodemother.ai.tools;

import cn.hutool.core.io.FileUtil;
import com.rush.rushaicodemother.ai.tools.policy.DependencyPolicyService;
import com.rush.rushaicodemother.constant.AppConstant;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.artifact.PatchApplyResult;
import com.rush.rushaicodemother.orchestration.patch.PatchOperation;
import com.rush.rushaicodemother.orchestration.tool.ToolExecutionGateway;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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

    @Test
    void installDependenciesMustBeDeferredToTheBuildPipeline() throws Exception {
        PackageToolFixture fixture = createFixture(4L);

        String result = fixture.tool().managePackageJson(
                "installDependencies", null, null, null, null, null, false, null, 4L
        );

        assertTrue(result.contains("已移交构建校验流水线"));
        assertTrue(result.contains("不重复执行 pnpm install"));
        verifyNoInteractions(fixture.gateway());
    }

    @Test
    void packageMutationMayRequestDeferredInstallWithoutStartingItInline() throws Exception {
        PackageToolFixture fixture = createFixture(5L);

        String result = fixture.tool().managePackageJson(
                "addDependency", "marked", "^12.0.0", "dependencies",
                null, null, true, "渲染 markdown", 5L
        );

        assertTrue(result.contains("已添加依赖"));
        assertTrue(result.contains("已移交构建校验流水线"));
        verify(fixture.gateway()).applyPatch(
                eq(5L), any(Path.class), any(PatchOperation.class),
                eq("tool-package-json"), eq("addDependency")
        );
    }

    @Test
    void fullStackProjectMustPatchFrontendPackageJsonFromTheWorkspaceRoot() throws Exception {
        PackageToolFixture fixture = createFixture(
                6L,
                CodeGenTypeEnum.FULL_STACK_PROJECT,
                "frontend/package.json"
        );
        ArgumentCaptor<Path> projectRootCaptor = ArgumentCaptor.forClass(Path.class);
        ArgumentCaptor<PatchOperation> operationCaptor = ArgumentCaptor.forClass(PatchOperation.class);

        String result = fixture.tool().managePackageJson(
                "addDependency", "marked", "^12.0.0", "dependencies",
                null, null, false, "渲染 markdown", 6L
        );

        assertTrue(result.contains("已添加依赖"));
        verify(fixture.gateway()).applyPatch(
                eq(6L), projectRootCaptor.capture(), operationCaptor.capture(),
                eq("tool-package-json"), eq("addDependency")
        );
        assertEquals(fixture.projectRoot().toRealPath(), projectRootCaptor.getValue());
        assertEquals("frontend/package.json", operationCaptor.getValue().relativePath());
        assertTrue(operationCaptor.getValue().content().contains("\"marked\""));
    }

    private PackageManagerTool createTool(long appId) throws Exception {
        return createFixture(appId).tool();
    }

    private PackageToolFixture createFixture(long appId) throws Exception {
        return createFixture(appId, CodeGenTypeEnum.VUE_PROJECT, "package.json");
    }

    private PackageToolFixture createFixture(
            long appId,
            CodeGenTypeEnum codeGenType,
            String packageJsonRelativePath
    ) throws Exception {
        Path projectDir = TEST_OUTPUT_ROOT.resolve(codeGenType.getValue() + "_" + appId);
        FileUtil.del(projectDir.toFile());
        Path packageJsonPath = projectDir.resolve(packageJsonRelativePath);
        Files.createDirectories(packageJsonPath.getParent());
        FileUtil.writeString("{\n  \"scripts\": {},\n  \"dependencies\": {}\n}",
                packageJsonPath.toFile(), StandardCharsets.UTF_8);
        ToolExecutionGateway gateway = mock(ToolExecutionGateway.class);
        when(gateway.applyPatch(anyLong(), any(Path.class), any(PatchOperation.class), anyString(), anyString()))
                .thenReturn(PatchApplyResult.applied(
                        appId,
                        "test-package-json",
                        projectDir.toString(),
                        1,
                        java.util.List.of(packageJsonRelativePath)
                ));
        PackageManagerTool tool = new PackageManagerTool(
                new DependencyPolicyService(),
                gateway,
                ToolPathSupportTestFixture.workspaceForApp(appId, codeGenType)
        );
        return new PackageToolFixture(tool, gateway, projectDir);
    }

    private record PackageToolFixture(
            PackageManagerTool tool,
            ToolExecutionGateway gateway,
            Path projectRoot
    ) {
    }
}
