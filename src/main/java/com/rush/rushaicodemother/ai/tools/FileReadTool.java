package com.rush.rushaicodemother.ai.tools;

import com.rush.rushaicodemother.infrastructure.diagnostic.LogExceptionSanitizer;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionPolicyException;
import com.rush.rushaicodemother.orchestration.tool.ToolPublicFailureException;
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

    /**
 * 读取文件。
 *
 * @param relativeFilePath {@code relativeFilePath} 对应的调用参数
 * @param appId 应用编号
 * @return 处理后的文件文本
 */
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
                throw toolFailure("错误：文件不存在或不是文件 - " + relativeFilePath);
            }
            return workspaceFileService.readUtf8(file);
        } catch (ToolPublicFailureException publicFailure) {
            throw publicFailure;
        } catch (GenerationExecutionPolicyException executionPolicyFailure) {
            throw executionPolicyFailure;
        } catch (ToolInputException e) {
            throw toolInputFailure("读取文件失败: ", e);
        } catch (Exception e) {
            log.error("读取文件失败，relativeFilePath: {}", relativeFilePath, LogExceptionSanitizer.sanitize(e));
            throw toolFailure("读取文件失败，请稍后重试");
        }
    }

    @Override
    public ToolRiskLevel getRiskLevel() {
        return ToolRiskLevel.READ_ONLY;
    }

    @Override
    public String getToolName() {
        return "readFile";
    }

    @Override
    public String getDisplayName() {
        return "读取文件";
    }

    /**
 * 将工具执行结果整理为模型可消费的文本。
 *
 * @param arguments 参数
 * @return 处理后的方法执行结果文本
 */
    @Override
    public String generateToolExecutedResult(JSONObject arguments) {
        String relativeFilePath = arguments.getStr("relativeFilePath");
        return String.format("[工具调用] %s %s", getDisplayName(), relativeFilePath);
    }
} 
