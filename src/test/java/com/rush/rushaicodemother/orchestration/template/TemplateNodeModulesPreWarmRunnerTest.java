package com.rush.rushaicodemother.orchestration.template;

import com.rush.rushaicodemother.config.TemplatePreWarmProperties;
import com.rush.rushaicodemother.config.WorkspaceFileSystemProperties;
import com.rush.rushaicodemother.infrastructure.filesystem.WorkspaceFileSystemService;
import com.rush.rushaicodemother.service.dependency.DependencyInstallResult;
import com.rush.rushaicodemother.service.dependency.ProjectDependencyInstaller;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.SyncTaskExecutor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TemplateNodeModulesPreWarmRunnerTest {

    @Test
    void shouldUseUnifiedMaterializerAndInstallerAndRetainSuccessfulDirectory() throws Exception {
        TemplatePreWarmService preWarmService = mock(TemplatePreWarmService.class);
        ProjectTemplateMaterializer materializer = mock(ProjectTemplateMaterializer.class);
        ProjectDependencyInstaller installer = mock(ProjectDependencyInstaller.class);
        WorkspaceFileSystemService fileSystemService = fileSystemService();
        AtomicReference<Path> installedProject = new AtomicReference<>();
        when(installer.ensureInstalled(any(Path.class))).thenAnswer(invocation -> {
            Path project = invocation.getArgument(0);
            installedProject.set(project);
            Files.createDirectories(project.resolve("node_modules"));
            return DependencyInstallResult.success("installed");
        });
        TemplateNodeModulesPreWarmRunner runner = runner(
                preWarmService,
                materializer,
                installer,
                enabledProperties("vue-web-basic"),
                fileSystemService
        );

        runner.onApplicationReady();

        Path project = installedProject.get();
        assertNotNull(project);
        verify(materializer).materializeIntoExistingDirectory(eq("vue-web-basic"), any(Path.class));
        verify(installer, times(1)).ensureInstalled(any(Path.class));
        verify(preWarmService).registerPreWarmedModules(eq("vue-web-basic"), any(Path.class));
        verify(installer, never()).cancel(project);

        runner.shutdown();

        verify(installer).cancel(project);
        assertFalse(Files.exists(project));
    }

    @Test
    void shouldDeleteFailedPreWarmDirectory() throws Exception {
        TemplatePreWarmService preWarmService = mock(TemplatePreWarmService.class);
        ProjectTemplateMaterializer materializer = mock(ProjectTemplateMaterializer.class);
        ProjectDependencyInstaller installer = mock(ProjectDependencyInstaller.class);
        AtomicReference<Path> installedProject = new AtomicReference<>();
        when(installer.ensureInstalled(any(Path.class))).thenAnswer(invocation -> {
            Path project = invocation.getArgument(0);
            installedProject.set(project);
            return DependencyInstallResult.failed(DependencyInstallResult.Status.FAILED, "", "install failed");
        });
        TemplateNodeModulesPreWarmRunner runner = runner(
                preWarmService,
                materializer,
                installer,
                enabledProperties("vue-web-basic"),
                fileSystemService()
        );

        runner.onApplicationReady();

        Path project = installedProject.get();
        assertNotNull(project);
        assertFalse(Files.exists(project));
        verify(preWarmService, never()).registerPreWarmedModules(any(String.class), any(Path.class));
    }

    @Test
    void shouldNotSubmitTasksWhenPreWarmIsDisabled() {
        TemplatePreWarmService preWarmService = mock(TemplatePreWarmService.class);
        ProjectTemplateMaterializer materializer = mock(ProjectTemplateMaterializer.class);
        ProjectDependencyInstaller installer = mock(ProjectDependencyInstaller.class);
        TemplatePreWarmProperties properties = new TemplatePreWarmProperties();
        properties.setEnabled(false);
        TemplateNodeModulesPreWarmRunner runner = runner(
                preWarmService,
                materializer,
                installer,
                properties,
                fileSystemService()
        );

        runner.onApplicationReady();

        verifyNoInteractions(preWarmService, materializer, installer);
    }

    private TemplateNodeModulesPreWarmRunner runner(TemplatePreWarmService preWarmService,
                                                    ProjectTemplateMaterializer materializer,
                                                    ProjectDependencyInstaller installer,
                                                    TemplatePreWarmProperties properties,
                                                    WorkspaceFileSystemService fileSystemService) {
        return new TemplateNodeModulesPreWarmRunner(
                preWarmService,
                materializer,
                installer,
                properties,
                new SyncTaskExecutor(),
                fileSystemService
        );
    }

    private WorkspaceFileSystemService fileSystemService() {
        return new WorkspaceFileSystemService(new WorkspaceFileSystemProperties());
    }

    private TemplatePreWarmProperties enabledProperties(String... templateIds) {
        TemplatePreWarmProperties properties = new TemplatePreWarmProperties();
        properties.setEnabled(true);
        properties.setTemplateIds(List.of(templateIds));
        return properties;
    }
}