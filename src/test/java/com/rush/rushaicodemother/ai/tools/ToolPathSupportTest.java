package com.rush.rushaicodemother.ai.tools;

import com.rush.rushaicodemother.config.CodeStorageProperties;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionFence;
import com.rush.rushaicodemother.orchestration.tool.GenerationToolExecutionContextService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolPathSupportTest {

    private Path tempDirectory;

    @BeforeEach
    void setUp() throws IOException {
        Path testRoot = Path.of("target", "test-work", "tool-path-support")
                .toAbsolutePath()
                .normalize();
        Files.createDirectories(testRoot);
        tempDirectory = Files.createDirectory(testRoot.resolve(UUID.randomUUID().toString()));
    }

    @Test
    void projectRootMustFollowBoundCodeTypeAndInjectedStorageRoot() {
        Map<CodeGenTypeEnum, String> expectedDirectoryNames = Map.of(
                CodeGenTypeEnum.VUE_PROJECT, "vue_project_990001",
                CodeGenTypeEnum.BACKEND_PROJECT, "backend_project_990001",
                CodeGenTypeEnum.FULL_STACK_PROJECT, "full_stack_project_990001"
        );

        expectedDirectoryNames.forEach((codeGenType, expectedDirectoryName) -> {
            ToolPathSupport pathSupport = pathSupportFor(990_001L, codeGenType);
            Path projectRoot = pathSupport.resolveProjectRoot(990_001L);

            assertEquals(expectedDirectoryName, projectRoot.getFileName().toString());
            assertTrue(projectRoot.startsWith(configuredOutputRoot()));
        });
    }

    @Test
    void missingExecutionContextMustFailInsteadOfFallingBackToVueProject() {
        ToolPathSupport pathSupport = ToolPathSupportTestFixture.from(
                new GenerationToolExecutionContextService(),
                storageProperties()
        );

        ToolInputException exception = assertThrows(
                ToolInputException.class,
                () -> pathSupport.resolveProjectRoot(990_002L)
        );

        assertTrue(exception.getMessage().contains("工具执行上下文不存在"));
    }

    @Test
    void fencedExecutionMustNotFallBackToCanonicalWorkspaceWhenIsolationIsMissing() {
        long appId = 990_007L;
        String taskId = "fenced-task";
        GenerationToolExecutionContextService contextService = new GenerationToolExecutionContextService();
        contextService.bindChangePlan(
                appId,
                taskId,
                "full_generation",
                CodeGenTypeEnum.VUE_PROJECT,
                null,
                true,
                "test",
                null,
                new GenerationExecutionFence(taskId, "worker-a", 1L)
        );
        ToolPathSupport pathSupport = ToolPathSupportTestFixture.from(contextService, storageProperties());

        ToolInputException exception = assertThrows(
                ToolInputException.class,
                () -> pathSupport.resolveProjectRoot(appId)
        );

        assertTrue(exception.getMessage().contains("缺少隔离工作区"));
        assertEquals(taskId, pathSupport.resolveTaskId(appId));
    }

    @Test
    void invalidApplicationIdMustBeRejectedBeforePathResolution() {
        ToolPathSupport pathSupport = ToolPathSupportTestFixture.from(
                new GenerationToolExecutionContextService(),
                storageProperties()
        );

        assertThrows(ToolInputException.class, () -> pathSupport.resolveProjectRoot(null));
        assertThrows(ToolInputException.class, () -> pathSupport.resolveProjectRoot(0L));
        assertThrows(ToolInputException.class, () -> pathSupport.resolveProjectRoot(-1L));
    }

    @Test
    void traversalAndAbsolutePathsOutsideWorkspaceMustBeRejected() {
        long appId = 990_003L;
        ToolPathSupport pathSupport = pathSupportFor(appId, CodeGenTypeEnum.VUE_PROJECT);
        Path outsidePath = configuredOutputRoot().getParent().resolve("outside-tool-workspace").normalize();

        assertThrows(ToolInputException.class, () -> pathSupport.resolvePath("../outside", appId));
        assertThrows(ToolInputException.class, () -> pathSupport.normalizeRelativePath("a/../../outside"));
        assertThrows(ToolInputException.class, () -> pathSupport.resolvePath(outsidePath.toString(), appId));
    }

    @Test
    void repeatedDotsInsideFileNameMustRemainValid() {
        ToolPathSupport pathSupport = pathSupportFor(990_004L, CodeGenTypeEnum.VUE_PROJECT);

        String normalizedPath = pathSupport.normalizeRelativePath("src/foo..bar.ts");

        assertEquals("src/foo..bar.ts", normalizedPath);
        assertFalse(normalizedPath.contains("../"));
    }

    @Test
    void projectRootSymbolicLinkMustBeRejectedEvenWhenTargetStaysInsideOutputRoot() throws Exception {
        long appId = 990_005L;
        ToolPathSupport pathSupport = pathSupportFor(appId, CodeGenTypeEnum.VUE_PROJECT);
        Path outputRoot = configuredOutputRoot();
        Path projectRoot = outputRoot.resolve("vue_project_" + appId);
        Path realDirectory = outputRoot.resolve("another-project");
        Files.createDirectories(realDirectory);
        createSymbolicLinkOrSkip(projectRoot, realDirectory);

        ToolInputException exception = assertThrows(
                ToolInputException.class,
                () -> pathSupport.resolveProjectRoot(appId)
        );

        // 拒绝由工作区解析层的 NOFOLLOW_LINKS 校验先行完成，因此对外是「路径无效」，
        // 成因保留在 cause 中。只要链接被拒绝，安全不变量即成立；不绑定具体提示语，
        // 避免把用户可见文案固化成契约。
        assertTrue(exception.getMessage().contains("项目工作区路径无效"));
        assertNotNull(exception.getCause());
        assertTrue(exception.getCause().getMessage().contains("不是安全目录"));
    }

    @Test
    void projectRootSymbolicLinkMustBeRejectedByToolBoundaryWhenWorkspaceIsSupplied() throws Exception {
        long appId = 990_007L;
        Path outputRoot = configuredOutputRoot();
        Path projectRoot = outputRoot.resolve("vue_project_" + appId);
        Path realDirectory = outputRoot.resolve("another-supplied-project");
        Files.createDirectories(realDirectory);
        createSymbolicLinkOrSkip(projectRoot, realDirectory);
        // 直接提供已存在的符号链接工作区，跳过解析层校验，验证工具边界自身的兜底拒绝。
        ToolPathSupport pathSupport = ToolPathSupportTestFixture.forSuppliedWorkspace(
                appId, CodeGenTypeEnum.VUE_PROJECT, projectRoot, storageProperties());

        ToolInputException exception = assertThrows(
                ToolInputException.class,
                () -> pathSupport.resolveProjectRoot(appId)
        );

        assertTrue(exception.getMessage().contains("不能是符号链接"));
    }

    @Test
    void symbolicLinkSegmentsMustBeRejectedWhenPlatformSupportsLinks() throws Exception {
        long appId = 990_006L;
        ToolPathSupport pathSupport = pathSupportFor(appId, CodeGenTypeEnum.VUE_PROJECT);
        Path projectRoot = pathSupport.resolveProjectRoot(appId);
        Path realDirectory = projectRoot.resolve("real-directory");
        Path symbolicLink = projectRoot.resolve("linked-directory");
        Files.createDirectories(realDirectory);
        createSymbolicLinkOrSkip(symbolicLink, realDirectory);

        assertThrows(
                ToolInputException.class,
                () -> pathSupport.resolvePath("linked-directory/file.txt", appId)
        );
    }

    private ToolPathSupport pathSupportFor(long appId, CodeGenTypeEnum codeGenType) {
        return ToolPathSupportTestFixture.forApp(appId, codeGenType, storageProperties());
    }

    private CodeStorageProperties storageProperties() {
        CodeStorageProperties properties = new CodeStorageProperties();
        properties.setOutputRootDir(configuredOutputRoot());
        properties.setDeployRootDir(tempDirectory.resolve("deploy").toAbsolutePath().normalize());
        return properties;
    }

    private Path configuredOutputRoot() {
        return tempDirectory.resolve("output").toAbsolutePath().normalize();
    }

    private void createSymbolicLinkOrSkip(Path link, Path target) throws IOException {
        try {
            Files.createSymbolicLink(link, target);
        } catch (IOException | UnsupportedOperationException | SecurityException exception) {
            Assumptions.abort("当前平台不允许创建符号链接: " + exception.getMessage());
        }
    }

    @AfterEach
    void tearDown() throws IOException {
        if (tempDirectory == null || !Files.exists(tempDirectory)) {
            return;
        }
        Files.walkFileTree(tempDirectory, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException exception) throws IOException {
                if (exception != null) {
                    throw exception;
                }
                Files.deleteIfExists(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
