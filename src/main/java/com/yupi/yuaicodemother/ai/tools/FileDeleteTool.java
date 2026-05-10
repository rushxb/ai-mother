package com.yupi.yuaicodemother.ai.tools;

import cn.hutool.json.JSONObject;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import lombok.extern.slf4j.Slf4j;
import com.yupi.yuaicodemother.orchestration.artifact.ChangePlan;
import com.yupi.yuaicodemother.orchestration.artifact.PatchApplyResult;
import com.yupi.yuaicodemother.orchestration.patch.GenerationPatchApplyService;
import com.yupi.yuaicodemother.orchestration.patch.PatchOperation;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 文件删除工具
 * 支持 AI 通过工具调用的方式删除文件
 */
@Slf4j
@Component
public class FileDeleteTool extends BaseTool {

    private final GenerationPatchApplyService generationPatchApplyService;

    public FileDeleteTool(GenerationPatchApplyService generationPatchApplyService) {
        this.generationPatchApplyService = generationPatchApplyService;
    }

    @Tool("删除指定路径的文件")
    public String deleteFile(
            @P("文件的相对路径")
            String relativeFilePath,
            @ToolMemoryId Long appId
    ) {
        try {
            String normalizedPath = ToolPathSupport.normalizeRelativePath(relativeFilePath);
            Path path = ToolPathSupport.resolvePath(normalizedPath, appId);
            if (!Files.exists(path)) {
                return "警告：文件不存在，无需删除 - " + normalizedPath;
            }
            if (!Files.isRegularFile(path)) {
                return "错误：指定路径不是文件，无法删除 - " + normalizedPath;
            }
            // 安全检查：避免删除重要文件
            String fileName = path.getFileName().toString();
            if (isImportantFile(fileName)) {
                return "错误：不允许删除重要文件 - " + fileName;
            }
            PatchApplyResult result = generationPatchApplyService.apply(
                    appId,
                    "tool-delete-file",
                    ToolPathSupport.resolveProjectRoot(appId),
                    new ChangePlan("v1", "single_file_patch", List.of(), List.of(), List.of(normalizedPath), List.of("workspace"), "review_only", "manual_retry_without_snapshot"),
                    List.of(PatchOperation.delete(normalizedPath))
            );
            if ("applied".equals(result.status())) {
                log.info("成功删除文件: {}", path.toAbsolutePath());
                return "文件删除成功: " + normalizedPath;
            }
            return "删除文件失败: " + normalizedPath + ", 原因: " + result.reason();
        } catch (IllegalArgumentException e) {
            return "删除文件失败: " + e.getMessage();
        } catch (Exception e) {
            String errorMessage = "删除文件失败: " + relativeFilePath + ", 错误: " + e.getMessage();
            log.error(errorMessage, e);
            return errorMessage;
        }
    }

    /**
     * 判断是否是重要文件，不允许删除
     */
    private boolean isImportantFile(String fileName) {
        String[] importantFiles = {
                "package.json", "package-lock.json", "yarn.lock", "pnpm-lock.yaml",
                "vite.config.js", "vite.config.ts", "vue.config.js",
                "tsconfig.json", "tsconfig.app.json", "tsconfig.node.json",
                "index.html", "main.js", "main.ts", "App.vue", ".gitignore", "README.md"
        };
        for (String important : importantFiles) {
            if (important.equalsIgnoreCase(fileName)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String getToolName() {
        return "deleteFile";
    }

    @Override
    public String getDisplayName() {
        return "删除文件";
    }

    @Override
    public String generateToolExecutedResult(JSONObject arguments) {
        String relativeFilePath = arguments.getStr("relativeFilePath");
        return String.format(" [工具调用] %s %s", getDisplayName(), relativeFilePath);
    }
}
