package com.rush.rushaicodemother.ai.tools;

import cn.hutool.core.io.FileUtil;
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
 * 文件写入工具
 * 支持 AI 通过工具调用的方式写入文件
 */
@Slf4j
@Component
public class FileWriteTool extends BaseTool {

    private final ToolExecutionGateway toolExecutionGateway;

    public FileWriteTool(ToolExecutionGateway toolExecutionGateway) {
        this.toolExecutionGateway = toolExecutionGateway;
    }

    @Tool("写入文件到指定路径")
    public String writeFile(
            @P("文件的相对路径")
            String relativeFilePath,
            @P("要写入文件的内容")
            String content,
            @ToolMemoryId Long appId
    ) {
        try {
            String normalizedPath = ToolPathSupport.normalizeRelativePath(relativeFilePath);
            Path projectRoot = ToolPathSupport.resolveProjectRoot(appId);
            boolean exists = ToolPathSupport.resolvePath(normalizedPath, appId).toFile().exists();
            PatchOperation operation = exists
                    ? PatchOperation.modify(normalizedPath, content)
                    : PatchOperation.add(normalizedPath, content);
            PatchApplyResult result = applyWithGlobalChangePlan(appId, projectRoot, operation);
            if ("applied".equals(result.status())) {
                log.info("成功写入文件: {}", ToolPathSupport.resolvePath(normalizedPath, appId).toAbsolutePath());
                return "文件写入成功: " + normalizedPath;
            }
            return "文件写入失败: " + normalizedPath + ", 原因: " + result.reason();
        } catch (IllegalArgumentException e) {
            return "文件写入失败: " + e.getMessage();
        } catch (Exception e) {
            String errorMessage = "文件写入失败: " + relativeFilePath + ", 错误: " + e.getMessage();
            log.error(errorMessage, e);
            return errorMessage;
        }
    }

    private PatchApplyResult applyWithGlobalChangePlan(Long appId, Path projectRoot, PatchOperation operation) {
        return toolExecutionGateway.applyPatch(appId, projectRoot, operation, "tool-write-file", "write_file");
    }

    @Override
    public String getToolName() {
        return "writeFile";
    }

    @Override
    public String getDisplayName() {
        return "写入文件";
    }

    @Override
    public String generateToolExecutedResult(JSONObject arguments) {
        String relativeFilePath = arguments.getStr("relativeFilePath");
        String suffix = FileUtil.getSuffix(relativeFilePath);
        String content = arguments.getStr("content");
        return String.format("""
                        [工具调用] %s %s
                        ```%s
                        %s
                        ```
                        """, getDisplayName(), relativeFilePath, suffix, content);
    }
}
