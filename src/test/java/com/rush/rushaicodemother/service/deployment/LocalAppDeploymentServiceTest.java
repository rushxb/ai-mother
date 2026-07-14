package com.rush.rushaicodemother.service.deployment;

import com.rush.rushaicodemother.config.CodeDeploymentProperties;
import com.rush.rushaicodemother.core.builder.VueProjectBuilder;
import com.rush.rushaicodemother.core.builder.VueBuildCommandResult;
import com.rush.rushaicodemother.core.builder.VueBuildResult;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.mapper.AppMapper;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.service.artifact.AppArtifactLifecycleService;
import com.rush.rushaicodemother.service.artifact.DeploymentArtifactTransaction;
import com.rush.rushaicodemother.service.lifecycle.AppOperationLockManager;
import com.rush.rushaicodemother.service.screenshot.ScreenshotService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.UUID;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class LocalAppDeploymentServiceTest {

    private Path tempDirectory;

    private AppArtifactLifecycleService artifactService;
    private VueProjectBuilder vueProjectBuilder;
    private ScreenshotService screenshotService;
    private AppMapper appMapper;
    private DeploymentArtifactTransaction artifactTransaction;
    private LocalAppDeploymentService deploymentService;

    @BeforeEach
    void setUp() throws IOException {
        tempDirectory = createTestDirectory("deployment");
        artifactService = mock(AppArtifactLifecycleService.class);
        vueProjectBuilder = mock(VueProjectBuilder.class);
        screenshotService = mock(ScreenshotService.class);
        when(screenshotService.isEnabled()).thenReturn(true);
        appMapper = mock(AppMapper.class);
        artifactTransaction = mock(DeploymentArtifactTransaction.class);
        deploymentService = deploymentService(() -> "FixedKey1234", Runnable::run);
    }

    @Test
    void shouldDeployHtmlWithCompensatableArtifactSwitchAndScreenshot() throws IOException {
        App app = app(11L, CodeGenTypeEnum.HTML, null);
        Path generatedDirectory = Files.createDirectories(tempDirectory.resolve("html_11"));
        when(appMapper.selectDeploymentState(11L)).thenReturn(app);
        when(artifactService.requireGeneratedDirectory(app)).thenReturn(generatedDirectory);
        when(artifactService.prepareDeployment(generatedDirectory, "FixedKey1234"))
                .thenReturn(artifactTransaction);
        when(appMapper.updateDeploymentMetadata(eq(11L), eq("FixedKey1234"), any(LocalDateTime.class)))
                .thenReturn(1);
        when(screenshotService.generateAndUploadScreenshot(
                "https://deploy.example.com/static/FixedKey1234/"))
                .thenReturn("https://cdn.example.com/cover.png");
        when(appMapper.updateCoverForDeployment(eq(11L), any(LocalDateTime.class),
                eq("https://cdn.example.com/cover.png"))).thenReturn(1);

        String deployUrl = deploymentService.deploy(app);

        assertEquals("https://deploy.example.com/static/FixedKey1234/", deployUrl);
        assertEquals("FixedKey1234", app.getDeployKey());
        assertNotNull(app.getDeployedTime());
        assertEquals(0, app.getDeployedTime().getNano());
        var ordered = inOrder(artifactTransaction, appMapper);
        ordered.verify(artifactTransaction).activate();
        ordered.verify(appMapper).updateDeploymentMetadata(eq(11L), eq("FixedKey1234"), any(LocalDateTime.class));
        ordered.verify(artifactTransaction).commit();
        verify(artifactTransaction, never()).rollback();
        verify(appMapper).updateCoverForDeployment(
                eq(11L), eq(app.getDeployedTime()), eq("https://cdn.example.com/cover.png"));
    }

    @Test
    void shouldRollbackArtifactSwitchWhenDatabaseUpdateFails() throws IOException {
        App app = app(21L, CodeGenTypeEnum.HTML, "Existing21");
        Path generatedDirectory = Files.createDirectories(tempDirectory.resolve("html_21"));
        when(appMapper.selectDeploymentState(21L)).thenReturn(app);
        when(artifactService.requireGeneratedDirectory(app)).thenReturn(generatedDirectory);
        when(artifactService.prepareDeployment(generatedDirectory, "Existing21"))
                .thenReturn(artifactTransaction);
        when(appMapper.updateDeploymentMetadata(eq(21L), eq("Existing21"), any(LocalDateTime.class)))
                .thenReturn(0);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> deploymentService.deploy(app));

        assertEquals(ErrorCode.OPERATION_ERROR.getCode(), exception.getCode());
        verify(artifactTransaction).activate();
        verify(artifactTransaction).rollback();
        verify(artifactTransaction, never()).commit();
        verifyNoInteractions(screenshotService);
    }

    @Test
    void shouldPreserveDeploymentAndRollbackFailures() throws IOException {
        App app = app(22L, CodeGenTypeEnum.HTML, "Existing22");
        Path generatedDirectory = Files.createDirectories(tempDirectory.resolve("html_22"));
        when(appMapper.selectDeploymentState(22L)).thenReturn(app);
        when(artifactService.requireGeneratedDirectory(app)).thenReturn(generatedDirectory);
        when(artifactService.prepareDeployment(generatedDirectory, "Existing22"))
                .thenReturn(artifactTransaction);
        when(appMapper.updateDeploymentMetadata(eq(22L), eq("Existing22"), any(LocalDateTime.class)))
                .thenReturn(0);
        BusinessException rollbackFailure = new BusinessException(ErrorCode.SYSTEM_ERROR, "rollback failed");
        org.mockito.Mockito.doThrow(rollbackFailure).when(artifactTransaction).rollback();

        BusinessException exception = assertThrows(BusinessException.class,
                () -> deploymentService.deploy(app));

        assertEquals(ErrorCode.SYSTEM_ERROR.getCode(), exception.getCode());
        assertEquals(ErrorCode.OPERATION_ERROR.getCode(), ((BusinessException) exception.getCause()).getCode());
        assertEquals(1, exception.getSuppressed().length);
        assertEquals(rollbackFailure, exception.getSuppressed()[0]);
        verify(artifactTransaction, never()).commit();
        verifyNoInteractions(screenshotService);
    }

    @Test
    void shouldBuildVueAndDeployOnlyDistDirectory() throws IOException {
        App app = app(31L, CodeGenTypeEnum.VUE_PROJECT, "VueKey31");
        Path generatedDirectory = Files.createDirectories(tempDirectory.resolve("vue_project_31"));
        Path distDirectory = Files.createDirectories(generatedDirectory.resolve("dist"));
        when(appMapper.selectDeploymentState(31L)).thenReturn(app);
        when(artifactService.requireGeneratedDirectory(app)).thenReturn(generatedDirectory);
        when(vueProjectBuilder.buildProjectWithResult(generatedDirectory.toString()))
                .thenReturn(new VueBuildResult(
                        true, "done", generatedDirectory.toString(), "ok", null, null
                ));
        when(artifactService.prepareDeployment(distDirectory, "VueKey31"))
                .thenReturn(artifactTransaction);
        when(appMapper.updateDeploymentMetadata(eq(31L), eq("VueKey31"), any(LocalDateTime.class)))
                .thenReturn(1);

        deploymentService.deploy(app);

        verify(vueProjectBuilder).buildProjectWithResult(generatedDirectory.toString());
        verify(artifactService).prepareDeployment(distDirectory, "VueKey31");
    }

    @Test
    void shouldRejectFailedVueBuildBeforePreparingDeployment() throws IOException {
        App app = app(41L, CodeGenTypeEnum.VUE_PROJECT, "VueKey41");
        Path generatedDirectory = Files.createDirectories(tempDirectory.resolve("vue_project_41"));
        when(appMapper.selectDeploymentState(41L)).thenReturn(app);
        when(artifactService.requireGeneratedDirectory(app)).thenReturn(generatedDirectory);
        VueBuildCommandResult failedCommand = new VueBuildCommandResult(
                "pnpm build", false, 1, false,
                "src/App.vue(12,3): error TS2307: Cannot find module '@/missing'\n"
                        + "Authorization: Bearer secret-value",
                null
        );
        when(vueProjectBuilder.buildProjectWithResult(generatedDirectory.toString()))
                .thenReturn(new VueBuildResult(
                        false, "build", generatedDirectory.toString(), "compile failed", null, failedCommand
                ));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> deploymentService.deploy(app));

        assertEquals(ErrorCode.SYSTEM_ERROR.getCode(), exception.getCode());
        assertTrue(exception.getMessage().contains("Cannot find module"));
        assertFalse(exception.getMessage().contains("secret-value"));
        verify(artifactService, never()).prepareDeployment(any(), any());
        verify(appMapper).selectDeploymentState(41L);
        verify(appMapper, never()).updateDeploymentMetadata(any(), any(), any());
    }

    @Test
    void shouldRejectUnsupportedBackendAndFullStackDeployments() {
        BusinessException backendException = assertThrows(BusinessException.class,
                () -> deploymentService.deploy(app(51L, CodeGenTypeEnum.BACKEND_PROJECT, null)));
        BusinessException fullStackException = assertThrows(BusinessException.class,
                () -> deploymentService.deploy(app(52L, CodeGenTypeEnum.FULL_STACK_PROJECT, null)));

        assertEquals(ErrorCode.OPERATION_ERROR.getCode(), backendException.getCode());
        assertEquals(ErrorCode.OPERATION_ERROR.getCode(), fullStackException.getCode());
        verifyNoInteractions(artifactService, vueProjectBuilder, appMapper, screenshotService);
    }

    @Test
    void shouldRejectSynchronizationBeforeInitialDeployment() {
        App currentApp = app(61L, CodeGenTypeEnum.HTML, null);
        when(appMapper.selectDeploymentState(61L)).thenReturn(currentApp);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> deploymentService.synchronize(app(61L, CodeGenTypeEnum.HTML, null)));

        assertEquals(ErrorCode.OPERATION_ERROR.getCode(), exception.getCode());
        verify(appMapper).selectDeploymentState(61L);
        verifyNoInteractions(artifactService, vueProjectBuilder, screenshotService);
        verify(appMapper, never()).updateDeploymentMetadata(any(), any(), any());
    }

    @Test
    void shouldRefreshDeploymentStateInsideLockAndReusePersistedKey() throws IOException {
        AtomicInteger deployKeyCalls = new AtomicInteger();
        deploymentService = deploymentService(
                () -> deployKeyCalls.incrementAndGet() == 1 ? "FirstKey123" : "SecondKey12",
                Runnable::run
        );
        App firstRequestedApp = app(71L, CodeGenTypeEnum.HTML, null);
        App secondRequestedApp = app(71L, CodeGenTypeEnum.HTML, null);
        App firstCurrentState = app(71L, CodeGenTypeEnum.HTML, null);
        App secondCurrentState = app(71L, CodeGenTypeEnum.HTML, "FirstKey123");
        Path generatedDirectory = Files.createDirectories(tempDirectory.resolve("html_71"));
        DeploymentArtifactTransaction firstTransaction = mock(DeploymentArtifactTransaction.class);
        DeploymentArtifactTransaction secondTransaction = mock(DeploymentArtifactTransaction.class);
        when(appMapper.selectDeploymentState(71L)).thenReturn(firstCurrentState, secondCurrentState);
        when(artifactService.requireGeneratedDirectory(any(App.class))).thenReturn(generatedDirectory);
        when(artifactService.prepareDeployment(generatedDirectory, "FirstKey123"))
                .thenReturn(firstTransaction, secondTransaction);
        when(appMapper.updateDeploymentMetadata(eq(71L), eq("FirstKey123"), any(LocalDateTime.class)))
                .thenReturn(1);

        String firstUrl = deploymentService.deploy(firstRequestedApp);
        String secondUrl = deploymentService.deploy(secondRequestedApp);

        assertEquals("https://deploy.example.com/static/FirstKey123/", firstUrl);
        assertEquals(firstUrl, secondUrl);
        assertEquals("FirstKey123", firstRequestedApp.getDeployKey());
        assertEquals("FirstKey123", secondRequestedApp.getDeployKey());
        assertEquals(1, deployKeyCalls.get());
        verify(artifactService, times(2)).prepareDeployment(generatedDirectory, "FirstKey123");
        verify(firstTransaction).commit();
        verify(secondTransaction).commit();
    }

    @Test
    void shouldDiscardStaleScreenshotTaskBeforeItCanOverwriteLatestCover() throws IOException {
        List<Runnable> screenshotTasks = new ArrayList<>();
        deploymentService = deploymentService(() -> "UnusedKey123", screenshotTasks::add);
        App firstCurrentState = app(81L, CodeGenTypeEnum.HTML, "Screenshot81");
        App secondCurrentState = app(81L, CodeGenTypeEnum.HTML, "Screenshot81");
        Path generatedDirectory = Files.createDirectories(tempDirectory.resolve("html_81"));
        DeploymentArtifactTransaction firstTransaction = mock(DeploymentArtifactTransaction.class);
        DeploymentArtifactTransaction secondTransaction = mock(DeploymentArtifactTransaction.class);
        when(appMapper.selectDeploymentState(81L)).thenReturn(firstCurrentState, secondCurrentState);
        when(artifactService.requireGeneratedDirectory(any(App.class))).thenReturn(generatedDirectory);
        when(artifactService.prepareDeployment(generatedDirectory, "Screenshot81"))
                .thenReturn(firstTransaction, secondTransaction);
        when(appMapper.updateDeploymentMetadata(eq(81L), eq("Screenshot81"), any(LocalDateTime.class)))
                .thenReturn(1);
        when(screenshotService.generateAndUploadScreenshot(
                "https://deploy.example.com/static/Screenshot81/"))
                .thenReturn("https://cdn.example.com/latest-cover.png");
        when(appMapper.updateCoverForDeployment(eq(81L), any(LocalDateTime.class),
                eq("https://cdn.example.com/latest-cover.png"))).thenReturn(1);

        deploymentService.deploy(app(81L, CodeGenTypeEnum.HTML, "Screenshot81"));
        deploymentService.deploy(app(81L, CodeGenTypeEnum.HTML, "Screenshot81"));

        assertEquals(2, screenshotTasks.size());
        screenshotTasks.get(0).run();
        verify(screenshotService, times(2)).isEnabled();
        verify(screenshotService, never()).generateAndUploadScreenshot(any());

        screenshotTasks.get(1).run();
        verify(screenshotService).generateAndUploadScreenshot(
                "https://deploy.example.com/static/Screenshot81/");
        verify(appMapper).updateCoverForDeployment(
                eq(81L), any(LocalDateTime.class), eq("https://cdn.example.com/latest-cover.png"));
    }

    @Test
    void shouldNotSubmitScreenshotTaskWhenScreenshotFeatureIsDisabled() throws IOException {
        when(screenshotService.isEnabled()).thenReturn(false);
        List<Runnable> screenshotTasks = new ArrayList<>();
        deploymentService = deploymentService(() -> "Disabled123", screenshotTasks::add);
        App app = app(91L, CodeGenTypeEnum.HTML, null);
        Path generatedDirectory = Files.createDirectories(tempDirectory.resolve("html_91"));
        when(appMapper.selectDeploymentState(91L)).thenReturn(app);
        when(artifactService.requireGeneratedDirectory(app)).thenReturn(generatedDirectory);
        when(artifactService.prepareDeployment(generatedDirectory, "Disabled123"))
                .thenReturn(artifactTransaction);
        when(appMapper.updateDeploymentMetadata(eq(91L), eq("Disabled123"), any(LocalDateTime.class)))
                .thenReturn(1);

        deploymentService.deploy(app);

        assertTrue(screenshotTasks.isEmpty());
        verify(screenshotService).isEnabled();
        verify(screenshotService, never()).generateAndUploadScreenshot(any());
    }

    private LocalAppDeploymentService deploymentService(DeploymentKeyGenerator deploymentKeyGenerator,
                                                         Executor screenshotExecutor) {
        CodeDeploymentProperties properties = new CodeDeploymentProperties();
        properties.setDeployHost("https://deploy.example.com/static/");
        return new LocalAppDeploymentService(
                artifactService,
                vueProjectBuilder,
                screenshotService,
                appMapper,
                properties,
                new AppOperationLockManager(),
                deploymentKeyGenerator,
                screenshotExecutor
        );
    }

    private App app(Long id, CodeGenTypeEnum codeGenType, String deployKey) {
        App app = new App();
        app.setId(id);
        app.setCodeGenType(codeGenType.getValue());
        app.setDeployKey(deployKey);
        return app;
    }
    @AfterEach
    void tearDown() throws IOException {
        deleteRecursively(tempDirectory);
    }

    private Path createTestDirectory(String prefix) throws IOException {
        Path testRoot = Path.of("target", "test-temp").toAbsolutePath().normalize();
        Files.createDirectories(testRoot);
        return Files.createDirectories(testRoot.resolve(prefix + "-" + UUID.randomUUID()));
    }

    private void deleteRecursively(Path root) throws IOException {
        if (root == null || !Files.exists(root)) {
            return;
        }
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
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
