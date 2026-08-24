package com.rush.rushaicodemother.ai.tools;

import com.rush.rushaicodemother.infrastructure.diagnostic.LogExceptionSanitizer;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionPolicyException;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 文件目录读取工具
 * 通过统一工作区模块执行有界目录遍历
 */
@Slf4j
@Component
public class FileDirReadTool extends BaseTool {

    private final ToolWorkspaceFileService workspaceFileService;

    public FileDirReadTool(ToolWorkspaceFileService workspaceFileService) {
        this.workspaceFileService = workspaceFileService;
    }

    /**
 * 读取{@code Dir}。
 *
 * @param relativeDirPath {@code relativeDirPath} 对应的调用参数
 * @param appId 应用编号
 * @return 处理后的{@code Dir}文本
 */
    @Tool("读取目录结构，获取指定目录下的所有文件和子目录信息")
    public String readDir(
            @P("目录的相对路径，为空则读取整个项目结构")
            String relativeDirPath,
            @ToolMemoryId Long appId
    ) {
        try {
            ToolWorkspaceFileService.DirectoryListing listing =
                    workspaceFileService.listDirectory(appId, relativeDirPath);
            StringBuilder structure = new StringBuilder();
            structure.append("项目目录结构:\n");
            for (ToolWorkspaceFileService.DirectoryEntry entry : listing.entries()) {
                structure.append("  ".repeat(entry.depth()))
                        .append(entry.fileName());
                if (entry.directory()) {
                    structure.append('/');
                }
                structure.append('\n');
            }
            if (listing.truncated()) {
                structure.append("... 目录内容超过安全遍历限制，已截断\n");
            }
            return structure.toString();
        } catch (GenerationExecutionPolicyException executionPolicyFailure) {
            throw executionPolicyFailure;
        } catch (ToolInputException e) {
            throw toolInputFailure("读取目录结构失败: ", e);
        } catch (Exception e) {
            log.error("读取目录结构失败，relativeDirPath: {}", relativeDirPath, LogExceptionSanitizer.sanitize(e));
            throw toolFailure("读取目录结构失败，请稍后重试");
        }
    }

    @Override
    public ToolRiskLevel getRiskLevel() {
        return ToolRiskLevel.READ_ONLY;
    }

    @Override
    public String getToolName() {
        return "readDir";
    }

    @Override
    public String getDisplayName() {
        return "读取目录";
    }

    /**
 * 将工具执行结果整理为模型可消费的文本。
 *
 * @param arguments 参数
 * @return 处理后的方法执行结果文本
 */
    @Override
    public String generateToolExecutedResult(JSONObject arguments) {
        String relativeDirPath = arguments.getStr("relativeDirPath");
        if (StrUtil.isEmpty(relativeDirPath)) {
            relativeDirPath = "根目录";
        }
        return String.format("[工具调用] %s %s", getDisplayName(), relativeDirPath);
    }
} 
