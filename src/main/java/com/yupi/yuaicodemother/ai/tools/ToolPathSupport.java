package com.yupi.yuaicodemother.ai.tools;

import com.yupi.yuaicodemother.constant.AppConstant;
import com.yupi.yuaicodemother.model.enums.CodeGenTypeEnum;
import com.yupi.yuaicodemother.orchestration.tool.GenerationToolExecutionContextService;
import com.yupi.yuaicodemother.service.AppService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * AI 工具路径辅助类
 */
@Component
public class ToolPathSupport {

    private static ObjectProvider<AppService> appServiceProvider;
    private static GenerationToolExecutionContextService toolExecutionContextService;

    public ToolPathSupport(ObjectProvider<AppService> appServiceProvider,
                           GenerationToolExecutionContextService toolExecutionContextService) {
        ToolPathSupport.appServiceProvider = appServiceProvider;
        ToolPathSupport.toolExecutionContextService = toolExecutionContextService;
    }

    static Path resolveProjectRoot(Long appId) {
        if (appId == null || appId <= 0) {
            throw new IllegalArgumentException("应用 ID 无效，无法定位项目工作区");
        }
        CodeGenTypeEnum codeGenType = resolveCodeGenType(appId);
        String projectDirName = codeGenType.getValue() + "_" + appId;
        return Paths.get(AppConstant.CODE_OUTPUT_ROOT_DIR, projectDirName)
                .toAbsolutePath()
                .normalize();
    }

    static Path resolvePath(String relativePath, Long appId) {
        Path projectRoot = resolveProjectRoot(appId);
        if (relativePath == null || relativePath.isBlank()) {
            return projectRoot;
        }
        Path inputPath = Paths.get(relativePath);
        Path resolvedPath = inputPath.isAbsolute() ? inputPath.normalize() : projectRoot.resolve(relativePath).normalize();
        ensureWithinProject(projectRoot, resolvedPath);
        return resolvedPath;
    }

    static String normalizeRelativePath(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            throw new IllegalArgumentException("文件路径不能为空");
        }
        String normalizedPath = relativePath.replace("\\", "/").trim();
        if (normalizedPath.startsWith("/") || normalizedPath.contains("..")) {
            throw new IllegalArgumentException("非法路径，超出当前项目目录范围");
        }
        return normalizedPath;
    }

    static void ensureWithinProject(Path projectRoot, Path targetPath) {
        Path normalizedRoot = projectRoot.toAbsolutePath().normalize();
        Path normalizedTarget = targetPath.toAbsolutePath().normalize();
        if (!normalizedTarget.startsWith(normalizedRoot)) {
            throw new IllegalArgumentException("非法路径，超出当前项目目录范围");
        }
    }

    private static CodeGenTypeEnum resolveCodeGenType(Long appId) {
        CodeGenTypeEnum contextType = toolExecutionContextService == null ? null : toolExecutionContextService.getContext(appId)
                .map(context -> context.codeGenType())
                .orElse(null);
        if (contextType != null) {
            return contextType;
        }
        AppService appService = appServiceProvider == null ? null : appServiceProvider.getIfAvailable();
        if (appService != null) {
            com.yupi.yuaicodemother.model.entity.App app = appService.getById(appId);
            CodeGenTypeEnum appType = CodeGenTypeEnum.getEnumByValue(app == null ? null : app.getCodeGenType());
            if (appType != null) {
                return appType;
            }
        }
        return CodeGenTypeEnum.VUE_PROJECT;
    }
}
