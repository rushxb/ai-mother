package com.rush.rushaicodemother.orchestration.template;

import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 模板 node_modules 预热运行器。
 * <p>
 * 在应用启动时预装模板的依赖，避免首次生成时执行 pnpm install。
 * 使用虚拟线程异步执行，不阻塞应用启动。
 */
@Slf4j
@Component
public class TemplateNodeModulesPreWarmRunner {

    private static final String TEMPLATE_ROOT = "project-templates";
    private static final List<String> VUE_TEMPLATE_IDS = List.of(
            "vue-web-basic",
            "vue-web-admin",
            "vue-web-mobile",
            "vue-web-landing"
    );

    private final TemplatePreWarmService templatePreWarmService;
    private final PathMatchingResourcePatternResolver resourceResolver;

    public TemplateNodeModulesPreWarmRunner(TemplatePreWarmService templatePreWarmService) {
        this.templatePreWarmService = templatePreWarmService;
        this.resourceResolver = new PathMatchingResourcePatternResolver();
    }

    /**
     * 应用启动后异步预热模板。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        // 使用虚拟线程异步执行，不阻塞应用启动
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (String templateId : VUE_TEMPLATE_IDS) {
                CompletableFuture.runAsync(() -> preWarmTemplate(templateId), executor);
            }
        }
        log.info("模板预热任务已提交");
    }

    /**
     * 预热单个模板。
     */
    private void preWarmTemplate(String templateId) {
        try {
            // 检查模板是否有 package.json
            String packageJsonPath = TEMPLATE_ROOT + "/" + templateId + "/package.json";
            Resource packageJsonResource = resourceResolver.getResource("classpath:" + packageJsonPath);

            if (!packageJsonResource.exists()) {
                log.debug("模板 {} 没有 package.json，跳过预热", templateId);
                return;
            }

            // 创建临时目录进行预热
            Path tempDir = Files.createTempDirectory("template-prewarm-" + templateId + "-");
            try {
                // 复制模板文件到临时目录
                copyTemplateToTemp(templateId, tempDir);

                // 执行 pnpm install
                boolean installSuccess = executePnpmInstall(tempDir.toFile());

                if (installSuccess) {
                    // 注册预热的 node_modules
                    templatePreWarmService.registerPreWarmedModules(templateId, tempDir.resolve("node_modules"));
                    log.info("模板 {} 预热成功", templateId);
                } else {
                    log.warn("模板 {} 预热失败：pnpm install 执行失败", templateId);
                    // 清理临时目录
                    deleteDirectory(tempDir.toFile());
                }
            } catch (Exception e) {
                log.warn("模板 {} 预热异常: {}", templateId, e.getMessage());
                // 清理临时目录
                deleteDirectory(tempDir.toFile());
            }
        } catch (Exception e) {
            log.debug("模板 {} 预热跳过: {}", templateId, e.getMessage());
        }
    }

    /**
     * 复制模板文件到临时目录。
     */
    private void copyTemplateToTemp(String templateId, Path tempDir) throws Exception {
        String templatePrefix = TEMPLATE_ROOT + "/" + templateId + "/";
        Resource[] resources = resourceResolver.getResources("classpath:" + templatePrefix + "**/*");

        for (Resource resource : resources) {
            if (!resource.exists() || !resource.isReadable()) {
                continue;
            }

            String relativePath = resolveRelativePath(resource, templatePrefix);
            if (StrUtil.isBlank(relativePath) || relativePath.endsWith("/")) {
                continue;
            }

            Path targetPath = tempDir.resolve(relativePath);
            Files.createDirectories(targetPath.getParent());
            Files.copy(resource.getInputStream(), targetPath);
        }
    }

    /**
     * 执行 pnpm install。
     */
    private boolean executePnpmInstall(File workingDir) {
        try {
            String command = isWindows() ? "pnpm.cmd" : "pnpm";
            ProcessBuilder processBuilder = new ProcessBuilder(command, "install", "--prefer-offline")
                    .directory(workingDir)
                    .redirectErrorStream(true);

            // 设置环境变量
            processBuilder.environment().put("NO_UPDATE_NOTIFIER", "1");
            processBuilder.environment().put("NPM_CONFIG_AUDIT", "false");
            processBuilder.environment().put("NPM_CONFIG_FUND", "false");

            Process process = processBuilder.start();
            boolean finished = process.waitFor(300, java.util.concurrent.TimeUnit.SECONDS);

            if (!finished) {
                process.destroyForcibly();
                log.warn("pnpm install 超时");
                return false;
            }

            int exitCode = process.exitValue();
            if (exitCode == 0) {
                log.info("pnpm install 执行成功: {}", workingDir.getAbsolutePath());
                return true;
            } else {
                log.warn("pnpm install 执行失败，退出码: {}", exitCode);
                return false;
            }
        } catch (Exception e) {
            log.warn("执行 pnpm install 失败: {}", e.getMessage());
            return false;
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

    /**
     * 删除目录。
     */
    private void deleteDirectory(File directory) {
        if (directory == null || !directory.exists()) {
            return;
        }
        File[] files = directory.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    deleteDirectory(file);
                } else {
                    file.delete();
                }
            }
        }
        directory.delete();
    }

    /**
     * 判断是否是 Windows 系统。
     */
    private boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("windows");
    }
}
