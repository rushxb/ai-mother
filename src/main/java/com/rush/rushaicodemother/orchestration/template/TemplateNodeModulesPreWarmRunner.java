package com.rush.rushaicodemother.orchestration.template;

import com.rush.rushaicodemother.config.TemplatePreWarmProperties;
import com.rush.rushaicodemother.infrastructure.diagnostic.LogExceptionSanitizer;
import com.rush.rushaicodemother.infrastructure.filesystem.WorkspaceFileSystemService;
import com.rush.rushaicodemother.service.dependency.DependencyInstallResult;
import com.rush.rushaicodemother.service.dependency.ProjectDependencyInstaller;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** Pre-warms Node.js template dependencies on an isolated bounded executor. */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.template-pre-warm", name = "enabled", havingValue = "true")
public class TemplateNodeModulesPreWarmRunner {

    private final TemplatePreWarmService templatePreWarmService;
    private final ProjectTemplateMaterializer templateMaterializer;
    private final ProjectDependencyInstaller projectDependencyInstaller;
    private final TemplatePreWarmProperties properties;
    private final TaskExecutor taskExecutor;
    private final WorkspaceFileSystemService workspaceFileSystemService;
    private final Set<Path> retainedTempDirectories = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);
    private final Object lifecycleMonitor = new Object();

    public TemplateNodeModulesPreWarmRunner(
            TemplatePreWarmService templatePreWarmService,
            ProjectTemplateMaterializer templateMaterializer,
            ProjectDependencyInstaller projectDependencyInstaller,
            TemplatePreWarmProperties properties,
            @Qualifier(TemplatePreWarmConfiguration.TEMPLATE_PRE_WARM_TASK_EXECUTOR) TaskExecutor taskExecutor,
            WorkspaceFileSystemService workspaceFileSystemService
    ) {
        this.templatePreWarmService = templatePreWarmService;
        this.templateMaterializer = templateMaterializer;
        this.projectDependencyInstaller = projectDependencyInstaller;
        this.properties = properties;
        this.taskExecutor = taskExecutor;
        this.workspaceFileSystemService = workspaceFileSystemService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if (!properties.isEnabled() || shuttingDown.get()) {
            log.debug("Template pre-warm is disabled or application shutdown has started");
            return;
        }

        List<String> templateIds = List.copyOf(properties.getTemplateIds());
        int submittedTaskCount = 0;
        for (String templateId : templateIds) {
            try {
                taskExecutor.execute(() -> preWarmTemplate(templateId));
                submittedTaskCount++;
            } catch (RuntimeException exception) {
                log.warn(
                        "Template pre-warm task submission failed: templateId={}, error={}",
                        templateId,
                        LogExceptionSanitizer.sanitizeMessage(exception)
                );
            }
        }
        log.info(
                "Template pre-warm tasks submitted: submitted={}, configured={}, maxConcurrency={}",
                submittedTaskCount,
                templateIds.size(),
                properties.getMaxConcurrency()
        );
    }

    private void preWarmTemplate(String templateId) {
        if (shuttingDown.get()) {
            return;
        }
        Path tempDirectory = null;
        boolean retained = false;
        try {
            tempDirectory = Files.createTempDirectory("template-prewarm-" + templateId + "-")
                    .toAbsolutePath()
                    .normalize();
            retainedTempDirectories.add(tempDirectory);
            templateMaterializer.materializeIntoExistingDirectory(templateId, tempDirectory);

            if (shuttingDown.get()) {
                projectDependencyInstaller.cancel(tempDirectory);
                return;
            }
            DependencyInstallResult installResult = projectDependencyInstaller.ensureInstalled(tempDirectory);
            if (!installResult.success()) {
                log.warn(
                        "Template pre-warm failed: templateId={}, status={}, error={}",
                        templateId,
                        installResult.status(),
                        installResult.errorDetail()
                );
                return;
            }

            synchronized (lifecycleMonitor) {
                if (shuttingDown.get()) {
                    projectDependencyInstaller.cancel(tempDirectory);
                    return;
                }
                templatePreWarmService.registerPreWarmedModules(
                        templateId,
                        tempDirectory.resolve("node_modules")
                );
                retained = true;
            }
            log.info("Template pre-warm completed: templateId={}", templateId);
        } catch (Exception exception) {
            log.warn(
                    "Template pre-warm failed with an exception: templateId={}, error={}",
                    templateId,
                    LogExceptionSanitizer.sanitizeMessage(exception)
            );
        } finally {
            if (tempDirectory != null && !retained) {
                retainedTempDirectories.remove(tempDirectory);
                deleteTempDirectory(tempDirectory);
            }
        }
    }

    @PreDestroy
    void shutdown() {
        List<Path> directoriesToDelete;
        synchronized (lifecycleMonitor) {
            shuttingDown.set(true);
            directoriesToDelete = List.copyOf(retainedTempDirectories);
        }
        for (Path tempDirectory : directoriesToDelete) {
            projectDependencyInstaller.cancel(tempDirectory);
            retainedTempDirectories.remove(tempDirectory);
            deleteTempDirectory(tempDirectory);
        }
    }

    private void deleteTempDirectory(Path directory) {
        if (directory == null || !Files.exists(directory)) {
            return;
        }
        try {
            workspaceFileSystemService.deleteDirectory(directory);
        } catch (IOException exception) {
            log.warn(
                    "Template pre-warm temporary directory cleanup failed: path={}, error={}",
                    directory,
                    LogExceptionSanitizer.sanitizeMessage(exception)
            );
        }
    }
}