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
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionPolicyException;
import com.rush.rushaicodemother.orchestration.tool.ToolPublicFailureException;
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
    private final ToolWorkspaceFileService workspaceFileService;

    public FileWriteTool(
            ToolExecutionGateway toolExecutionGateway,
            ToolWorkspaceFileService workspaceFileService
    ) {
        this.toolExecutionGateway = toolExecutionGateway;
        this.workspaceFileService = workspaceFileService;
    }

    /**
 * 写入文件。
 *
 * @param relativeFilePath {@code relativeFilePath} 对应的调用参数
 * @param content 文件或消息内容
 * @param appId 应用编号
 * @return 处理后的文件文本
 */
    @Tool("写入文件到指定路径")
    public String writeFile(
            @P("文件的相对路径")
            String relativeFilePath,
            @P("要写入文件的内容")
            String content,
            @ToolMemoryId Long appId
    ) {
        // 将可能失败的操作收敛在统一异常边界内，便于清理资源和转换错误。
        try {
            if (content == null) {
                throw new ToolInputException("文件内容不能为 null");
            }
            ToolWorkspaceFileService.ToolWorkspaceFile file =
                    workspaceFileService.resolveFile(appId, relativeFilePath);
            String normalizedPath = file.relativePath();
            Path projectRoot = file.projectRoot();
            boolean exists = workspaceFileService.exists(file);
            if (exists && !workspaceFileService.isRegularFile(file)) {
                throw toolFailure("文件写入失败: 指定路径不是普通文件 - " + normalizedPath);
            }
            PatchOperation operation = exists
                    ? PatchOperation.modify(normalizedPath, content)
                    : PatchOperation.add(normalizedPath, content);
            PatchApplyResult result = applyWithGlobalChangePlan(appId, projectRoot, operation);
            if ("applied".equals(result.status())) {
                log.info("成功写入文件: {}", file.absolutePath());
                return "文件写入成功: " + normalizedPath;
            }
            throw toolFailure("文件写入失败: " + normalizedPath + ", 原因: " + result.reason());
        } catch (ToolInputException e) {
            throw toolInputFailure("文件写入失败: ", e);
        } catch (ToolPublicFailureException publicFailure) {
            throw publicFailure;
        } catch (GenerationExecutionPolicyException policyFailure) {
            throw policyFailure;
        } catch (Exception e) {
            log.error("文件写入失败，relativeFilePath: {}", relativeFilePath,
                    LogExceptionSanitizer.sanitize(e));
            throw toolFailure("文件写入失败，请稍后重试");
        }
    }

    private PatchApplyResult applyWithGlobalChangePlan(Long appId, Path projectRoot, PatchOperation operation) {
        return toolExecutionGateway.applyPatch(appId, projectRoot, operation, "tool-write-file", "write_file");
    }

    @Override
    public ToolRiskLevel getRiskLevel() {
        return ToolRiskLevel.WRITE;
    }

    @Override
    public boolean canMutateWorkspace() {
        return true;
    }

    @Override
    public String getToolName() {
        return "writeFile";
    }

    @Override
    public String getDisplayName() {
        return "写入文件";
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
        return String.format(
                "[工具调用] %s %s（内容已写入工作区，可在代码面板查看）",
                getDisplayName(), relativeFilePath
        );
    }
}
