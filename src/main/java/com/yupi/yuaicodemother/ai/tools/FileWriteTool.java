package com.yupi.yuaicodemother.ai.tools;

import cn.hutool.core.io.FileUtil;
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

import java.nio.file.Path;
import java.util.List;

/**
 * 文件写入工具
 * 支持 AI 通过工具调用的方式写入文件
 */
@Slf4j
@Component
public class FileWriteTool extends BaseTool {

    private final GenerationPatchApplyService generationPatchApplyService;

    public FileWriteTool(GenerationPatchApplyService generationPatchApplyService) {
        this.generationPatchApplyService = generationPatchApplyService;
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
            PatchApplyResult result;
            if (ToolPathSupport.resolvePath(normalizedPath, appId).toFile().exists()) {
                result = generationPatchApplyService.apply(
                        appId,
                        "tool-write-file",
                        projectRoot,
                        new ChangePlan("v1", "single_file_patch", List.of(), List.of(normalizedPath), List.of(), List.of("workspace"), "review_only", "manual_retry_without_snapshot"),
                        List.of(PatchOperation.modify(normalizedPath, content))
                );
            } else {
                result = generationPatchApplyService.apply(
                        appId,
                        "tool-write-file",
                        projectRoot,
                        new ChangePlan("v1", "single_file_patch", List.of(normalizedPath), List.of(), List.of(), List.of("workspace"), "review_only", "manual_retry_without_snapshot"),
                        List.of(PatchOperation.add(normalizedPath, content))
                );
            }
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
