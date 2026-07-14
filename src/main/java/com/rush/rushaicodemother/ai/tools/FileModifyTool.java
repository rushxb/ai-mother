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
 * 文件修改工具
 * 支持 AI 通过工具调用的方式修改文件内容
 */
@Slf4j
@Component
public class FileModifyTool extends BaseTool {

    private final ToolExecutionGateway toolExecutionGateway;
    private final ToolWorkspaceFileService workspaceFileService;

    public FileModifyTool(
            ToolExecutionGateway toolExecutionGateway,
            ToolWorkspaceFileService workspaceFileService
    ) {
        this.toolExecutionGateway = toolExecutionGateway;
        this.workspaceFileService = workspaceFileService;
    }

    @Tool("修改文件内容，用新内容替换指定的旧内容")
    public String modifyFile(
            @P("文件的相对路径")
            String relativeFilePath,
            @P("要替换的旧内容")
            String oldContent,
            @P("替换后的新内容")
            String newContent,
            @ToolMemoryId Long appId
    ) {
        try {
            validateReplacement(oldContent, newContent);
            ToolWorkspaceFileService.ToolWorkspaceFile file =
                    workspaceFileService.resolveFile(appId, relativeFilePath);
            String normalizedPath = file.relativePath();
            if (!workspaceFileService.exists(file) || !workspaceFileService.isRegularFile(file)) {
                return "错误：文件不存在或不是文件 - " + normalizedPath;
            }
            if (!workspaceFileService.readUtf8(file).contains(oldContent)) {
                return "警告：文件中未找到要替换的内容，文件未修改 - " + normalizedPath;
            }
            PatchApplyResult result = applyWithGlobalChangePlan(
                    appId,
                    file.projectRoot(),
                    PatchOperation.replace(normalizedPath, oldContent, newContent)
            );
            if ("applied".equals(result.status())) {
                log.info("成功修改文件: {}", file.absolutePath());
                return "文件修改成功: " + normalizedPath;
            }
            return "修改文件失败: " + normalizedPath + ", 原因: " + result.reason();
        } catch (ToolInputException e) {
            return renderInputError("修改文件失败: ", e);
        } catch (Exception e) {
            log.error("修改文件失败，relativeFilePath: {}", relativeFilePath, LogExceptionSanitizer.sanitize(e));
            return "修改文件失败，请稍后重试";
        }
    }

    private void validateReplacement(String oldContent, String newContent) {
        if (oldContent == null || oldContent.isEmpty()) {
            throw new ToolInputException("要替换的旧内容不能为空");
        }
        if (newContent == null) {
            throw new ToolInputException("替换后的新内容不能为 null");
        }
    }

    private PatchApplyResult applyWithGlobalChangePlan(Long appId, Path projectRoot, PatchOperation operation) {
        return toolExecutionGateway.applyPatch(appId, projectRoot, operation, "tool-modify-file", "modify_file");
    }

    @Override
    public String getToolName() {
        return "modifyFile";
    }

    @Override
    public String getDisplayName() {
        return "修改文件";
    }

    @Override
    public String generateToolExecutedResult(JSONObject arguments) {
        String relativeFilePath = arguments.getStr("relativeFilePath");
        String oldContent = arguments.getStr("oldContent");
        String newContent = arguments.getStr("newContent");
        // 显示对比内容
        return String.format("""
                [工具调用] %s %s
                
                替换前：
                ```
                %s
                ```
                
                替换后：
                ```
                %s
                ```
                """, getDisplayName(), relativeFilePath, oldContent, newContent);
    }
}
