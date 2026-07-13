package com.rush.rushaicodemother.orchestration.template;

import cn.hutool.core.util.StrUtil;
import com.rush.rushaicodemother.config.TemplatePreWarmProperties;
import com.rush.rushaicodemother.service.dependency.DependencyInstallResult;
import com.rush.rushaicodemother.service.dependency.ProjectDependencyInstaller;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 模板 node_modules 预热运行器。
 * <p>
 * 在应用启动时预装模板的依赖，避免首次生成时执行 pnpm install。
 * 预热任务使用独立的有界线程池执行，不占用应用通用异步执行资源。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.template-pre-warm", name = "enabled", havingValue = "true")
public class TemplateNodeModulesPreWarmRunner {

    private static final String TEMPLATE_ROOT = "project-templates";

    private final TemplatePreWarmService templatePreWarmService;
    private final ProjectDependencyInstaller projectDependencyInstaller;
    private final TemplatePreWarmProperties properties;
    private final TaskExecutor taskExecutor;
    private final PathMatchingResourcePatternResolver resourceResolver;
    private final Set<Path> retainedTempDirectories = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);
    private final Object lifecycleMonitor = new Object();

    public TemplateNodeModulesPreWarmRunner(
            TemplatePreWarmService templatePreWarmService,
            ProjectDependencyInstaller projectDependencyInstaller,
            TemplatePreWarmProperties properties,
            @Qualifier(TemplatePreWarmConfiguration.TEMPLATE_PRE_WARM_TASK_EXECUTOR) TaskExecutor taskExecutor
    ) {
        this.templatePreWarmService = templatePreWarmService;
        this.projectDependencyInstaller = projectDependencyInstaller;
        this.properties = properties;
        this.taskExecutor = taskExecutor;
        this.resourceResolver = new PathMatchingResourcePatternResolver();
    }

    /**
     * 应用启动后异步预热模板。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if (!properties.isEnabled() || shuttingDown.get()) {
            log.debug("模板预热未启用或应用正在关闭，跳过任务提交");
            return;
        }

        List<String> templateIds = List.copyOf(properties.getTemplateIds());
        int submittedTaskCount = 0;
        for (String templateId : templateIds) {
            try {
                taskExecutor.execute(() -> preWarmTemplate(templateId));
                submittedTaskCount++;
            } catch (RuntimeException exception) {
                log.warn("模板 {} 预热任务提交失败: {}", templateId, exception.getMessage());
            }
        }
        log.info(
                "模板预热任务已提交: submitted={}, configured={}, maxConcurrency={}",
                submittedTaskCount,
                templateIds.size(),
                properties.getMaxConcurrency()
        );
    }

    /**
     * 预热单个模板。
     */
    private void preWarmTemplate(String templateId) {
        if (shuttingDown.get()) {
            return;
        }
        Path tempDir = null;
        boolean retained = false;
        try {
            String packageJsonPath = TEMPLATE_ROOT + "/" + templateId + "/package.json";
            Resource packageJsonResource = resourceResolver.getResource("classpath:" + packageJsonPath);

            if (!packageJsonResource.exists()) {
                log.debug("模板 {} 没有 package.json，跳过预热", templateId);
                return;
            }

            tempDir = Files.createTempDirectory("template-prewarm-" + templateId + "-")
                    .toAbsolutePath()
                    .normalize();
            retainedTempDirectories.add(tempDir);
            copyTemplateToTemp(templateId, tempDir);

            if (shuttingDown.get()) {
                projectDependencyInstaller.cancel(tempDir);
                return;
            }
            DependencyInstallResult installResult = projectDependencyInstaller.ensureInstalled(tempDir);
            if (!installResult.success()) {
                log.warn("模板 {} 预热失败: status={}, error={}",
                        templateId, installResult.status(), installResult.errorDetail());
                return;
            }

            synchronized (lifecycleMonitor) {
                if (shuttingDown.get()) {
                    projectDependencyInstaller.cancel(tempDir);
                    return;
                }
                templatePreWarmService.registerPreWarmedModules(templateId, tempDir.resolve("node_modules"));
                retained = true;
            }
            log.info("模板 {} 预热成功", templateId);
        } catch (Exception exception) {
            log.warn("模板 {} 预热异常: {}", templateId, exception.getMessage());
        } finally {
            if (tempDir != null && !retained) {
                retainedTempDirectories.remove(tempDir);
                deleteTempDirectory(tempDir);
            }
        }
    }

    /**
     * 复制模板文件到临时目录。
     */
    private void copyTemplateToTemp(String templateId, Path tempDir) throws Exception {
        String templatePrefix = TEMPLATE_ROOT + "/" + templateId + "/";
        Resource[] resources = resourceResolver.getResources("classpath:" + templatePrefix + "**/*");
        Path safeRoot = tempDir.toAbsolutePath().normalize();

        for (Resource resource : resources) {
            if (!resource.exists() || !resource.isReadable()) {
                continue;
            }

            String relativePath = resolveRelativePath(resource, templatePrefix);
            if (StrUtil.isBlank(relativePath) || relativePath.endsWith("/")) {
                continue;
            }

            Path targetPath = safeRoot.resolve(relativePath).normalize();
            if (!targetPath.startsWith(safeRoot)) {
                throw new IOException("模板资源路径越界: " + relativePath);
            }
            Files.createDirectories(targetPath.getParent());
            try (InputStream inputStream = resource.getInputStream()) {
                Files.copy(inputStream, targetPath);
            }
        }
    }

    /**
     * 解析相对路径。
     */
    private String resolveRelativePath(Resource resource, String templatePrefix) throws Exception {
        String url = resource.getURL().toString();
        int index = url.indexOf(templatePrefix);
        if (index < 0) {
            return "";
        }
        return url.substring(index + templatePrefix.length());
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
            Files.walkFileTree(directory, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path visitedDirectory, IOException exception)
                        throws IOException {
                    if (exception != null) {
                        throw exception;
                    }
                    Files.deleteIfExists(visitedDirectory);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException exception) {
            log.warn("清理模板预热临时目录失败: path={}, error={}", directory, exception.getMessage());
        }
    }
}
