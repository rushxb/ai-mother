package com.rush.rushaicodemother.ai.tools;

import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
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
 * Resolves and validates file-system paths used by AI tools.
 *
 * <p>The project type must come from the bound tool execution context. Falling back to a default project type
 * can silently direct a tool to another workspace, so missing context is treated as an explicit input error.</p>
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
        CodeGenTypeEnum codeGenType = resolveCodeGenType(appId);
        try {
            GenerationWorkspace workspace = generationWorkspaceService.resolve(appId, codeGenType);
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

    private CodeGenTypeEnum resolveCodeGenType(Long appId) {
        return toolExecutionContextService.getContext(appId)
                .map(context -> context.codeGenType())
                .filter(type -> type != null)
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
