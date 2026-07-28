package com.rush.rushaicodemother.orchestration.template;

import com.rush.rushaicodemother.config.TemplatePreWarmProperties;
import com.rush.rushaicodemother.infrastructure.diagnostic.LogExceptionSanitizer;
import com.rush.rushaicodemother.infrastructure.filesystem.WorkspaceFileSystemService;
import com.rush.rushaicodemother.service.dependency.DependencyInstallResult;
import com.rush.rushaicodemother.service.dependency.DependencyInstallMode;
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

/** 在隔离的有界执行器中预热 Node.js 模板依赖的共享 pnpm store。 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.template-pre-warm", name = "enabled", havingValue = "true")
public class TemplateNodeModulesPreWarmRunner {

    private final ProjectTemplateMaterializer templateMaterializer;
    private final ProjectDependencyInstaller projectDependencyInstaller;
    private final TemplatePreWarmProperties properties;
    private final TaskExecutor taskExecutor;
    private final WorkspaceFileSystemService workspaceFileSystemService;
    private final Set<Path> activeTempDirectories = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);
    private final Object lifecycleMonitor = new Object();

    public TemplateNodeModulesPreWarmRunner(
            ProjectTemplateMaterializer templateMaterializer,
            ProjectDependencyInstaller projectDependencyInstaller,
            TemplatePreWarmProperties properties,
            @Qualifier(TemplatePreWarmConfiguration.TEMPLATE_PRE_WARM_TASK_EXECUTOR) TaskExecutor taskExecutor,
            WorkspaceFileSystemService workspaceFileSystemService
    ) {
        this.templateMaterializer = templateMaterializer;
        this.projectDependencyInstaller = projectDependencyInstaller;
        this.properties = properties;
        this.taskExecutor = taskExecutor;
        this.workspaceFileSystemService = workspaceFileSystemService;
    }

    /** 响应应用就绪事件。 */
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

    /** 处理进入前{@code Warm}模板。 */
    private void preWarmTemplate(String templateId) {
        // 先处理前置条件和快速返回分支，避免无效输入进入核心流程。
        if (shuttingDown.get()) {
            return;
        }
        Path tempDirectory = null;
        // 将可能失败的操作收敛在统一异常边界内，便于清理资源和转换错误。
        try {
            tempDirectory = Files.createTempDirectory("template-prewarm-" + templateId + "-")
                    .toAbsolutePath()
                    .normalize();
            activeTempDirectories.add(tempDirectory);
            templateMaterializer.materializeIntoExistingDirectory(templateId, tempDirectory);

            if (shuttingDown.get()) {
                projectDependencyInstaller.cancel(tempDirectory);
                return;
            }
            DependencyInstallResult installResult = projectDependencyInstaller.ensureInstalled(
                    tempDirectory,
                    null,
                    DependencyInstallMode.REFRESH_FROM_LOCKFILE
            );
            if (!installResult.success()) {
                log.warn(
                        "Template pre-warm failed: templateId={}, status={}, error={}",
                        templateId,
                        installResult.status(),
                        installResult.errorDetail()
                );
                return;
            }

            if (shuttingDown.get()) {
                projectDependencyInstaller.cancel(tempDirectory);
                return;
            }
            log.info("模板依赖共享 pnpm store 预热完成：templateId={}", templateId);
        } catch (Exception exception) {
            log.warn(
                    "Template pre-warm failed with an exception: templateId={}, error={}",
                    templateId,
                    LogExceptionSanitizer.sanitizeMessage(exception)
            );
        } finally {
            if (tempDirectory != null) {
                activeTempDirectories.remove(tempDirectory);
                deleteTempDirectory(tempDirectory);
            }
        }
    }

    /** 处理{@code shutdown}。 */
    @PreDestroy
    void shutdown() {
        List<Path> directoriesToDelete;
        synchronized (lifecycleMonitor) {
            shuttingDown.set(true);
            directoriesToDelete = List.copyOf(activeTempDirectories);
        }
        for (Path tempDirectory : directoriesToDelete) {
            projectDependencyInstaller.cancel(tempDirectory);
            activeTempDirectories.remove(tempDirectory);
            deleteTempDirectory(tempDirectory);
        }
    }

    /** 删除{@code Temp}目录。 */
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
