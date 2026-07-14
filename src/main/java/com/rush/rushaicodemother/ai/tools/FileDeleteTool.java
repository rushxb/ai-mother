package com.rush.rushaicodemother.ai.tools;

import com.rush.rushaicodemother.infrastructure.diagnostic.LogExceptionSanitizer;
import cn.hutool.json.JSONObject;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import lombok.extern.slf4j.Slf4j;
import com.rush.rushaicodemother.orchestration.artifact.PatchApplyResult;
import com.rush.rushaicodemother.orchestration.patch.PatchOperation;
import com.rush.rushaicodemother.orchestration.tool.ToolExecutionGateway;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/**
 * 文件删除工具
 * 支持 AI 通过工具调用的方式删除文件
 */
@Slf4j
@Component
public class FileDeleteTool extends BaseTool {

    private final ToolExecutionGateway toolExecutionGateway;
    private final ToolWorkspaceFileService workspaceFileService;

    public FileDeleteTool(
            ToolExecutionGateway toolExecutionGateway,
            ToolWorkspaceFileService workspaceFileService
    ) {
        this.toolExecutionGateway = toolExecutionGateway;
        this.workspaceFileService = workspaceFileService;
    }

    @Tool("删除指定路径的文件")
    public String deleteFile(
            @P("文件的相对路径")
            String relativeFilePath,
            @ToolMemoryId Long appId
    ) {
        try {
            ToolWorkspaceFileService.ToolWorkspaceFile file =
                    workspaceFileService.resolveFile(appId, relativeFilePath);
            String normalizedPath = file.relativePath();
            if (!workspaceFileService.exists(file)) {
                return "警告：文件不存在，无需删除 - " + normalizedPath;
            }
            if (!workspaceFileService.isRegularFile(file)) {
                return "错误：指定路径不是文件，无法删除 - " + normalizedPath;
            }
            // 安全检查：避免删除重要文件
            String fileName = file.fileName();
            if (isImportantFile(fileName)) {
                return "错误：不允许删除重要文件 - " + fileName;
            }
            PatchApplyResult result = applyWithGlobalChangePlan(
                    appId,
                    file.projectRoot(),
                    PatchOperation.delete(normalizedPath)
            );
            if ("applied".equals(result.status())) {
                log.info("成功删除文件: {}", file.absolutePath());
                return "文件删除成功: " + normalizedPath;
            }
            return "删除文件失败: " + normalizedPath + ", 原因: " + result.reason();
        } catch (ToolInputException e) {
            return renderInputError("删除文件失败: ", e);
        } catch (Exception e) {
            log.error("删除文件失败，relativeFilePath: {}", relativeFilePath, LogExceptionSanitizer.sanitize(e));
            return "删除文件失败，请稍后重试";
        }
    }

    private PatchApplyResult applyWithGlobalChangePlan(Long appId, Path projectRoot, PatchOperation operation) {
        return toolExecutionGateway.applyPatch(appId, projectRoot, operation, "tool-delete-file", "delete_file");
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
