package com.rush.rushaicodemother.ai.tools;

import com.rush.rushaicodemother.infrastructure.diagnostic.LogExceptionSanitizer;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionPolicyException;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

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

    private final ToolWorkspaceFileService workspaceFileService;

    public ReadMultipleFilesTool(ToolWorkspaceFileService workspaceFileService) {
        this.workspaceFileService = workspaceFileService;
    }

    /**
 * 读取{@code Multiple}文件。
 *
 * @param relativeFilePaths 待处理的 {@code relativeFilePaths} 集合
 * @param maxCharsPerFile {@code maxCharsPerFile} 对应的调用参数
 * @param appId 应用编号
 * @return 处理后的{@code Multiple}文件文本
 */
    @Tool("一次读取多个文件内容，适合在修改前批量获取项目上下文。")
    public String readMultipleFiles(
            @P("要读取的相对文件路径列表")
            List<String> relativeFilePaths,
            @P("每个文件最多返回的字符数，建议 8000 以内")
            Integer maxCharsPerFile,
            @ToolMemoryId Long appId
    ) {
        // 先处理前置条件和快速返回分支，避免无效输入进入核心流程。
        if (relativeFilePaths == null || relativeFilePaths.isEmpty()) {
            throw toolFailure("错误：文件路径列表不能为空");
        }
        if (relativeFilePaths.size() > MAX_FILES) {
            throw toolFailure("错误：单次最多读取 " + MAX_FILES + " 个文件");
        }
        int requestedCharLimit = maxCharsPerFile == null ? DEFAULT_MAX_CHARS : maxCharsPerFile;
        int charLimit = Math.max(1, Math.min(requestedCharLimit, MAX_CHARS_LIMIT));
        StringBuilder builder = new StringBuilder();
        int successfulReadCount = 0;
        // 按既定顺序逐项处理，并在达到资源或状态边界时提前结束。
        for (String relativeFilePath : relativeFilePaths) {
            try {
                ToolWorkspaceFileService.ToolWorkspaceFile file =
                        workspaceFileService.resolveFile(appId, relativeFilePath);
                if (!workspaceFileService.exists(file) || !workspaceFileService.isRegularFile(file)) {
                    builder.append("[文件] ").append(relativeFilePath).append('\n')
                            .append("错误：文件不存在\n\n");
                    continue;
                }
                String content = workspaceFileService.readUtf8(file);
                successfulReadCount++;
                builder.append("[文件] ").append(file.relativePath()).append('\n')
                        .append("```").append(resolveFenceLanguage(file.fileName())).append('\n')
                        .append(truncate(content, charLimit))
                        .append("\n```\n\n");
            } catch (GenerationExecutionPolicyException executionPolicyFailure) {
                throw executionPolicyFailure;
            } catch (ToolInputException e) {
                builder.append("[文件] ").append(relativeFilePath).append('\n')
                        .append(renderInputError(e)).append("\n\n");
            } catch (Exception e) {
                log.error("批量读取文件失败: {}", relativeFilePath, LogExceptionSanitizer.sanitize(e));
                builder.append("[文件] ").append(relativeFilePath).append('\n')
                        .append("错误：文件读取失败，请稍后重试\n\n");
            }
        }
        if (successfulReadCount == 0) {
            throw toolFailure("错误：批量读取失败，没有成功读取任何文件");
        }
        return StrUtil.trim(builder.toString());
    }

    /** 按资源上限截断{@code Read}{@code Multiple}文件工具。 */
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
    public ToolRiskLevel getRiskLevel() {
        return ToolRiskLevel.READ_ONLY;
    }

    @Override
    public String getToolName() {
        return "readMultipleFiles";
    }

    @Override
    public String getDisplayName() {
        return "批量读取文件";
    }

    /**
 * 将工具执行结果整理为模型可消费的文本。
 *
 * @param arguments 参数
 * @return 处理后的方法执行结果文本
 */
    @Override
    public String generateToolExecutedResult(JSONObject arguments) {
        Object filePaths = arguments.get("relativeFilePaths");
        return String.format("[工具调用] %s %s", getDisplayName(), filePaths);
    }

    /**
 * 将工具执行结果整理为模型可消费的文本。
 *
 * @param arguments 参数
 * @param toolResult 工具结果
 * @return 处理后的方法执行结果文本
 */
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
