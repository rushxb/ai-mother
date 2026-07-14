package com.rush.rushaicodemother.ai.tools;

import com.rush.rushaicodemother.infrastructure.diagnostic.LogExceptionSanitizer;
import cn.hutool.json.JSONObject;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 文件读取工具
 * 支持 AI 通过工具调用的方式读取文件内容
 */
@Slf4j
@Component
public class FileReadTool extends BaseTool {

    private final ToolWorkspaceFileService workspaceFileService;

    public FileReadTool(ToolWorkspaceFileService workspaceFileService) {
        this.workspaceFileService = workspaceFileService;
    }

    @Tool("读取指定路径的文件内容")
    public String readFile(
            @P("文件的相对路径")
            String relativeFilePath,
            @ToolMemoryId Long appId
    ) {
        try {
            ToolWorkspaceFileService.ToolWorkspaceFile file =
                    workspaceFileService.resolveFile(appId, relativeFilePath);
            if (!workspaceFileService.exists(file) || !workspaceFileService.isRegularFile(file)) {
                return "错误：文件不存在或不是文件 - " + relativeFilePath;
            }
            return workspaceFileService.readUtf8(file);
        } catch (ToolInputException e) {
            return renderInputError("读取文件失败: ", e);
        } catch (Exception e) {
            log.error("读取文件失败，relativeFilePath: {}", relativeFilePath, LogExceptionSanitizer.sanitize(e));
            return "读取文件失败，请稍后重试";
        }
    }

    @Override
    public String getToolName() {
        return "readFile";
    }

    @Override
    public String getDisplayName() {
        return "读取文件";
    }

    @Override
    public String generateToolExecutedResult(JSONObject arguments) {
        String relativeFilePath = arguments.getStr("relativeFilePath");
        return String.format("[工具调用] %s %s", getDisplayName(), relativeFilePath);
    }
} 
