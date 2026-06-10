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
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

/**
 * 批量读取文件工具
 */
@Slf4j
@Component
public class ReadMultipleFilesTool extends BaseTool {

    private static final int MAX_FILES = 10;
    private static final int DEFAULT_MAX_CHARS = 8000;
    private static final int MAX_CHARS_LIMIT = 20000;

    @Tool("一次读取多个文件内容，适合在修改前批量获取项目上下文。")
    public String readMultipleFiles(
            @P("要读取的相对文件路径列表")
            List<String> relativeFilePaths,
            @P("每个文件最多返回的字符数，建议 8000 以内")
            Integer maxCharsPerFile,
            @ToolMemoryId Long appId
    ) {
        if (relativeFilePaths == null || relativeFilePaths.isEmpty()) {
            return "错误：文件路径列表不能为空";
        }
        if (relativeFilePaths.size() > MAX_FILES) {
            return "错误：单次最多读取 " + MAX_FILES + " 个文件";
        }
        int charLimit = maxCharsPerFile == null ? DEFAULT_MAX_CHARS : Math.min(maxCharsPerFile, MAX_CHARS_LIMIT);
        StringBuilder builder = new StringBuilder();
        for (String relativeFilePath : relativeFilePaths) {
            try {
                Path filePath = ToolPathSupport.resolvePath(relativeFilePath, appId);
                File file = filePath.toFile();
                if (!file.exists() || !file.isFile()) {
                    builder.append("[文件] ").append(relativeFilePath).append('\n')
                            .append("错误：文件不存在\n\n");
                    continue;
                }
                String content = FileUtil.readString(file, StandardCharsets.UTF_8);
                builder.append("[文件] ").append(relativeFilePath).append('\n')
                        .append("```").append(resolveFenceLanguage(file.getName())).append('\n')
                        .append(truncate(content, charLimit))
                        .append("\n```\n\n");
            } catch (IllegalArgumentException e) {
                builder.append("[文件] ").append(relativeFilePath).append('\n')
                        .append("错误：").append(e.getMessage()).append("\n\n");
            } catch (Exception e) {
                log.error("批量读取文件失败: {}", relativeFilePath, e);
                builder.append("[文件] ").append(relativeFilePath).append('\n')
                        .append("错误：").append(e.getMessage()).append("\n\n");
            }
        }
        return StrUtil.trim(builder.toString());
    }

    private String truncate(String content, int charLimit) {
        if (content == null) {
            return "";
        }
        if (content.length() <= charLimit) {
            return content;
        }
        return content.substring(0, charLimit) + "\n// 文件内容过长，已截断";
    }

    private String resolveFenceLanguage(String fileName) {
        String suffix = FileUtil.getSuffix(fileName);
        return StrUtil.blankToDefault(suffix, "text");
    }

    @Override
    public String getToolName() {
        return "readMultipleFiles";
    }

    @Override
    public String getDisplayName() {
        return "批量读取文件";
    }

    @Override
    public String generateToolExecutedResult(JSONObject arguments) {
        Object filePaths = arguments.get("relativeFilePaths");
        return String.format("[工具调用] %s %s", getDisplayName(), filePaths);
    }

    @Override
    public String generateToolExecutedResult(JSONObject arguments, String toolResult) {
        return generateToolExecutedResult(arguments) + "\n" + summarizeResult(toolResult, 320);
    }

    private String summarizeResult(String toolResult, int maxChars) {
        if (StrUtil.isBlank(toolResult)) {
            return "";
        }
        String normalized = toolResult.replace("\r", " ").replace("\n", " ").trim();
        return StrUtil.sub(normalized, 0, Math.min(normalized.length(), maxChars));
    }
}
