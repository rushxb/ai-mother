package com.rush.rushaicodemother.ai.tools;

import cn.hutool.core.io.FileUtil;
import com.rush.rushaicodemother.ai.tools.policy.DependencyPolicyService;
import com.rush.rushaicodemother.constant.AppConstant;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.artifact.PatchApplyResult;
import com.rush.rushaicodemother.orchestration.patch.PatchOperation;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionCancelledException;
import com.rush.rushaicodemother.orchestration.tool.ToolExecutionGateway;
import com.rush.rushaicodemother.orchestration.tool.ToolPublicFailureException;
import com.rush.rushaicodemother.orchestration.tool.ToolResultEvidence;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.service.tool.ToolExecutionResult;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

        ToolPublicFailureException failure = assertThrows(
                ToolPublicFailureException.class,
                () -> tool.managePackageJson(
                        "setScript", null, null, null,
                        "clean", "rm -rf /", false, null, 1L)
        );

        assertTrue(failure.publicMessage().contains("依赖策略拒绝"));
    }

    @Test
    void managePackageJsonShouldRejectDependencyWithoutReason() throws Exception {
        PackageManagerTool tool = createTool(2L);

        ToolPublicFailureException failure = assertThrows(
                ToolPublicFailureException.class,
                () -> tool.managePackageJson(
                        "addDependency", "marked", "^12.0.0", "dependencies",
                        null, null, false, "", 2L)
        );

        assertTrue(failure.publicMessage().contains("reason"));
    }

    @Test
    void managePackageJsonShouldRecordApprovedDependencyReason() throws Exception {
        PackageManagerTool tool = createTool(3L);

        Object result = tool.managePackageJson(
                "addDependency", "marked", "^12.0.0", "dependencies", null, null, false, "渲染 markdown", 3L
        );
        String displayResult = assertInstanceOf(TextContent.class, result).text();

        assertTrue(displayResult.contains("已添加依赖"));
        assertTrue(displayResult.contains("package: marked"));
        assertTrue(displayResult.contains("dependencyType: dependencies"));
        assertTrue(displayResult.contains("reason: 渲染 markdown"));
        assertEquals(List.of("package.json"), effectivePaths(result));
    }

    @Test
    void installDependenciesMustBeDeferredToTheBuildPipeline() throws Exception {
        PackageToolFixture fixture = createFixture(4L);

        String result = fixture.tool().managePackageJson(
                "installDependencies", null, null, null, null, null, false, null, 4L
        ).text();

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
        ).text();

        assertTrue(result.contains("已添加依赖"));
        assertTrue(result.contains("已移交构建校验流水线"));
        verify(fixture.gateway()).applyPatch(
                eq(5L), any(Path.class), any(PatchOperation.class),
                eq("tool-package-json"), eq("addDependency")
        );
    }

    @Test
    void rejectedPackagePatchMustBeReportedAsProtocolFailure() throws Exception {
        PackageToolFixture fixture = createFixture(8L);
        when(fixture.gateway().applyPatch(
                anyLong(), any(Path.class), any(PatchOperation.class), anyString(), anyString()))
                .thenReturn(PatchApplyResult.rejected(
                        8L,
                        "task-8",
                        fixture.projectRoot().toString(),
                        1,
                        java.util.List.of("modify:package.json"),
                        "补丁超出冻结计划"
                ));

        ToolPublicFailureException failure = assertThrows(
                ToolPublicFailureException.class,
                () -> fixture.tool().managePackageJson(
                        "addDependency", "marked", "^12.0.0", "dependencies",
                        null, null, false, "渲染 markdown", 8L)
        );

        assertTrue(failure.publicMessage().contains("package.json 写入被拒绝"));
        assertTrue(failure.publicMessage().contains("补丁超出冻结计划"));
    }

    @Test
    void missingPackageJsonMustBeReportedAsProtocolFailure() throws Exception {
        PackageToolFixture fixture = createFixture(9L);
        Files.delete(fixture.projectRoot().resolve("package.json"));

        ToolPublicFailureException failure = assertThrows(
                ToolPublicFailureException.class,
                () -> fixture.tool().managePackageJson(
                        "getPackageJson", null, null, null,
                        null, null, false, null, 9L)
        );

        assertEquals("错误：package.json 不存在", failure.publicMessage());
        verifyNoInteractions(fixture.gateway());
    }

    @Test
    void malformedPackageJsonMustReturnSanitizedProtocolFailure() throws Exception {
        PackageToolFixture fixture = createFixture(10L);
        Files.writeString(fixture.projectRoot().resolve("package.json"), "{not-json");

        ToolPublicFailureException failure = assertThrows(
                ToolPublicFailureException.class,
                () -> fixture.tool().managePackageJson(
                        "getPackageJson", null, null, null,
                        null, null, false, null, 10L)
        );

        assertEquals("管理 package.json 失败，请稍后重试", failure.publicMessage());
        assertTrue(failure.getCause() == null, "协议失败不得携带可能含源码或路径的解析异常");
        verifyNoInteractions(fixture.gateway());
    }

    @Test
    void invalidWorkspaceInputMustBeReportedAsProtocolFailure() throws Exception {
        PackageToolFixture fixture = createFixture(11L);

        ToolPublicFailureException failure = assertThrows(
                ToolPublicFailureException.class,
                () -> fixture.tool().managePackageJson(
                        "getPackageJson", null, null, null,
                        null, null, false, null, null)
        );

        assertEquals("错误：应用 ID 无效，无法定位项目工作区", failure.publicMessage());
        verifyNoInteractions(fixture.gateway());
    }

    @Test
    void unsupportedPackageActionMustBeReportedAsProtocolFailure() throws Exception {
        PackageToolFixture fixture = createFixture(12L);

        ToolPublicFailureException failure = assertThrows(
                ToolPublicFailureException.class,
                () -> fixture.tool().managePackageJson(
                        "executeShell", null, null, null,
                        null, null, false, null, 12L)
        );

        assertEquals("错误：不支持的操作类型 - executeShell", failure.publicMessage());
        verifyNoInteractions(fixture.gateway());
    }

    @Test
    void rejectedDependencyRemovalMustBeReportedAsProtocolFailure() throws Exception {
        PackageToolFixture fixture = createFixture(13L);

        ToolPublicFailureException failure = assertThrows(
                ToolPublicFailureException.class,
                () -> fixture.tool().managePackageJson(
                        "removeDependency", "https://evil.example/package", null, "dependencies",
                        null, null, false, null, 13L)
        );

        assertTrue(failure.publicMessage().contains("依赖策略拒绝"));
        verifyNoInteractions(fixture.gateway());
    }

    @Test
    void blankScriptRemovalMustBeReportedAsProtocolFailure() throws Exception {
        PackageToolFixture fixture = createFixture(14L);

        ToolPublicFailureException failure = assertThrows(
                ToolPublicFailureException.class,
                () -> fixture.tool().managePackageJson(
                        "removeScript", null, null, null,
                        " ", null, false, null, 14L)
        );

        assertEquals("错误：脚本名称不能为空", failure.publicMessage());
        verifyNoInteractions(fixture.gateway());
    }

    @Test
    void removingMissingDependencyMustRemainAnIdempotentNoOp() throws Exception {
        PackageToolFixture fixture = createFixture(15L);

        Object result = fixture.tool().managePackageJson(
                "removeDependency", "marked", null, "dependencies",
                null, null, false, null, 15L
        );

        TextContent content = assertInstanceOf(TextContent.class, result,
                "package.json 工具也必须复用统一结果证据协议");
        assertTrue(content.text().contains("未找到依赖"));
        assertTrue(effectivePaths(result).isEmpty());
        verifyNoInteractions(fixture.gateway());
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
        ).text();

        assertTrue(result.contains("已添加依赖"));
        verify(fixture.gateway()).applyPatch(
                eq(6L), projectRootCaptor.capture(), operationCaptor.capture(),
                eq("tool-package-json"), eq("addDependency")
        );
        assertEquals(fixture.projectRoot().toRealPath(), projectRootCaptor.getValue());
        assertEquals("frontend/package.json", operationCaptor.getValue().relativePath());
        assertTrue(operationCaptor.getValue().content().contains("\"marked\""));
    }

    @Test
    void executionCancellationMustNotBeRenderedAsAnOrdinaryPackageError() throws Exception {
        PackageToolFixture fixture = createFixture(7L);
        GenerationExecutionCancelledException cancellation =
                new GenerationExecutionCancelledException("user_cancelled");
        when(fixture.gateway().applyPatch(
                anyLong(), any(Path.class), any(PatchOperation.class), anyString(), anyString()))
                .thenThrow(cancellation);

        GenerationExecutionCancelledException thrown = assertThrows(
                GenerationExecutionCancelledException.class,
                () -> fixture.tool().managePackageJson(
                        "addDependency", "marked", "^12.0.0", "dependencies",
                        null, null, false, "渲染 markdown", 7L)
        );

        assertSame(cancellation, thrown);
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

    private List<String> effectivePaths(Object result) {
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .id("package-result")
                .name("managePackageJson")
                .arguments("{\"action\":\"removeDependency\"}")
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
