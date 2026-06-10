package com.rush.rushaicodemother.ai.tools;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 文件目录读取工具
 * 使用 Hutool 简化文件操作
 */
@Slf4j
@Component
public class FileDirReadTool extends BaseTool {

    /**
     * 需要忽略的文件和目录
     */
    private static final Set<String> IGNORED_NAMES = Set.of(
            "node_modules", ".git", "dist", "build", ".DS_Store",
            ".env", "target", ".mvn", ".idea", ".vscode", "coverage"
    );

    /**
     * 需要忽略的文件扩展名
     */
    private static final Set<String> IGNORED_EXTENSIONS = Set.of(
            ".log", ".tmp", ".cache", ".lock"
    );

    @Tool("读取目录结构，获取指定目录下的所有文件和子目录信息")
    public String readDir(
            @P("目录的相对路径，为空则读取整个项目结构")
            String relativeDirPath,
            @ToolMemoryId Long appId
    ) {
        try {
            Path path = ToolPathSupport.resolvePath(relativeDirPath, appId);
            File targetDir = path.toFile();
            if (!targetDir.exists() || !targetDir.isDirectory()) {
                return "错误：目录不存在或不是目录 - " + relativeDirPath;
            }
            StringBuilder structure = new StringBuilder();
            structure.append("项目目录结构:\n");
            try (Stream<Path> pathStream = Files.walk(targetDir.toPath())) {
                List<File> allFiles = pathStream
                        .filter(walkPath -> !walkPath.equals(targetDir.toPath()))
                        .map(Path::toFile)
                        .filter(file -> !shouldIgnorePath(file.toPath()))
                        .sorted((f1, f2) -> {
                            int depth1 = getRelativeDepth(targetDir, f1);
                            int depth2 = getRelativeDepth(targetDir, f2);
                            if (depth1 != depth2) {
                                return Integer.compare(depth1, depth2);
                            }
                            if (f1.isDirectory() != f2.isDirectory()) {
                                return f1.isDirectory() ? -1 : 1;
                            }
                            return f1.getPath().compareTo(f2.getPath());
                        })
                        .toList();
                allFiles.forEach(file -> {
                    int depth = getRelativeDepth(targetDir, file);
                    String indent = "  ".repeat(depth);
                    structure.append(indent)
                            .append(file.getName());
                    if (file.isDirectory()) {
                        structure.append("/");
                    }
                    structure.append("\n");
                });
            }
            return structure.toString();
        } catch (IllegalArgumentException e) {
            return "读取目录结构失败: " + e.getMessage();
        } catch (Exception e) {
            String errorMessage = "读取目录结构失败: " + relativeDirPath + ", 错误: " + e.getMessage();
            log.error(errorMessage, e);
            return errorMessage;
        }
    }

    /**
     * 计算文件相对于根目录的深度
     */
    private int getRelativeDepth(File root, File file) {
        Path rootPath = root.toPath();
        Path filePath = file.toPath();
        return rootPath.relativize(filePath).getNameCount() - 1;
    }

    /**
     * 判断是否应该忽略该文件或目录
     */
    private boolean shouldIgnore(String fileName) {
        // 检查是否在忽略名称列表中
        if (IGNORED_NAMES.contains(fileName)) {
            return true;
        }

        // 检查文件扩展名
        return IGNORED_EXTENSIONS.stream().anyMatch(fileName::endsWith);
    }

    private boolean shouldIgnorePath(Path path) {
        for (Path part : path) {
            if (shouldIgnore(part.toString())) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String getToolName() {
        return "readDir";
    }

    @Override
    public String getDisplayName() {
        return "读取目录";
    }

    @Override
    public String generateToolExecutedResult(JSONObject arguments) {
        String relativeDirPath = arguments.getStr("relativeDirPath");
        if (StrUtil.isEmpty(relativeDirPath)) {
            relativeDirPath = "根目录";
        }
        return String.format("[工具调用] %s %s", getDisplayName(), relativeDirPath);
    }
} 
