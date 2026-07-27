package com.rush.rushaicodemother.ai.tools;

import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.tool.GenerationToolExecutionContext;
import com.rush.rushaicodemother.orchestration.tool.GenerationToolExecutionContextService;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspaceService;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;

/**
 * 解析并验证 AI 工具使用的文件系统路径。
 *
 * <p>项目类型必须来自绑定的工具执行上下文。回退到默认项目类型
 * 可以默默地将工具定向到另一个工作区，因此缺少上下文将被视为显式输入错误。</p>
 */
@Component
public class ToolPathSupport {

    private final GenerationToolExecutionContextService toolExecutionContextService;
    private final GenerationWorkspaceService generationWorkspaceService;

    public ToolPathSupport(
            GenerationToolExecutionContextService toolExecutionContextService,
            GenerationWorkspaceService generationWorkspaceService
    ) {
        this.toolExecutionContextService = Objects.requireNonNull(
                toolExecutionContextService,
                "toolExecutionContextService must not be null"
        );
        this.generationWorkspaceService = Objects.requireNonNull(
                generationWorkspaceService,
                "generationWorkspaceService must not be null"
        );
    }

    Path resolveProjectRoot(Long appId) {
        if (appId == null || appId <= 0) {
            throw new ToolInputException("应用 ID 无效，无法定位项目工作区");
        }
        GenerationToolExecutionContext context = requireContext(appId);
        CodeGenTypeEnum codeGenType = context.codeGenType();
        if (codeGenType == null) {
            throw new ToolInputException("工具执行上下文缺少代码生成类型");
        }
        try {
            GenerationWorkspace workspace = context.workspace();
            if (context.executionFence() != null && workspace == null) {
                throw new ToolInputException("受管工具执行上下文缺少隔离工作区");
            }
            if (workspace == null) {
                workspace = generationWorkspaceService.resolve(appId, codeGenType);
            }
            if (!appId.equals(workspace.appId()) || workspace.codeGenType() != codeGenType) {
                throw new ToolInputException("工具工作区上下文不匹配");
            }
            if (Files.isSymbolicLink(workspace.rootPath())) {
                throw new ToolInputException("项目工作区不能是符号链接");
            }
            return workspace.canonicalRootPath();
        } catch (BusinessException exception) {
            throw new ToolInputException("项目工作区路径无效", exception);
        }
    }

    Path resolvePath(String path, Long appId) {
        Path projectRoot = resolveProjectRoot(appId);
        if (path == null || path.isBlank()) {
            return projectRoot;
        }
        try {
            Path inputPath = Paths.get(path.trim());
            Path resolvedPath = inputPath.isAbsolute()
                    ? inputPath.toAbsolutePath().normalize()
                    : projectRoot.resolve(inputPath).normalize();
            ensureWithinProject(projectRoot, resolvedPath);
            rejectSymbolicLinks(projectRoot, resolvedPath);
            return resolvedPath;
        } catch (InvalidPathException e) {
            throw new ToolInputException("文件路径格式错误");
        }
    }

    String resolveTaskId(Long appId) {
        String taskId = requireContext(appId).taskId();
        if (taskId == null || taskId.isBlank()) {
            throw new ToolInputException("工具执行上下文缺少任务标识");
        }
        return taskId.trim();
    }

    String normalizeRelativePath(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            throw new ToolInputException("文件路径不能为空");
        }
        try {
            Path inputPath = Paths.get(relativePath.trim().replace('\\', '/'));
            if (inputPath.isAbsolute()) {
                throw new ToolInputException("非法路径，超出当前项目目录范围");
            }
            for (Path segment : inputPath) {
                if ("..".equals(segment.toString())) {
                    throw new ToolInputException("非法路径，超出当前项目目录范围");
                }
            }
            String normalizedPath = inputPath.normalize().toString().replace('\\', '/');
            if (normalizedPath.isBlank() || ".".equals(normalizedPath)) {
                throw new ToolInputException("文件路径不能为空");
            }
            return normalizedPath;
        } catch (InvalidPathException e) {
            throw new ToolInputException("文件路径格式错误");
        }
    }

    void ensureWithinProject(Path projectRoot, Path targetPath) {
        if (projectRoot == null || targetPath == null) {
            throw new ToolInputException("项目路径不能为空");
        }
        Path normalizedRoot = projectRoot.toAbsolutePath().normalize();
        Path normalizedTarget = targetPath.toAbsolutePath().normalize();
        if (!normalizedTarget.startsWith(normalizedRoot)) {
            throw new ToolInputException("非法路径，超出当前项目目录范围");
        }
    }

    private GenerationToolExecutionContext requireContext(Long appId) {
        return toolExecutionContextService.getContext(appId)
                .orElseThrow(() -> new ToolInputException("工具执行上下文不存在，无法定位项目工作区"));
    }

    private void rejectSymbolicLinks(Path projectRoot, Path targetPath) {
        Path currentPath = projectRoot.toAbsolutePath().normalize();
        Path relativePath = currentPath.relativize(targetPath.toAbsolutePath().normalize());
        for (Path segment : relativePath) {
            currentPath = currentPath.resolve(segment);
            if (Files.isSymbolicLink(currentPath)) {
                throw new ToolInputException("项目路径不能经过符号链接");
            }
        }
    }
}
