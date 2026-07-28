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

    /**
 * 返回{@code modify}文件。
 *
 * @param relativeFilePath {@code relativeFilePath} 对应的调用参数
 * @param oldContent {@code oldContent} 对应的调用参数
 * @param newContent {@code newContent} 对应的调用参数
 * @param appId 应用编号
 * @return 处理后的文件{@code Modify}工具文本
 */
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
        // 将可能失败的操作收敛在统一异常边界内，便于清理资源和转换错误。
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
        } catch (GenerationExecutionPolicyException policyFailure) {
            throw policyFailure;
        } catch (Exception e) {
            log.error("修改文件失败，relativeFilePath: {}", relativeFilePath, LogExceptionSanitizer.sanitize(e));
            return "修改文件失败，请稍后重试";
        }
    }

    /** 校验{@code ate}替换内容是否有效。 */
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
    public ToolRiskLevel getRiskLevel() {
        return ToolRiskLevel.WRITE;
    }

    @Override
    public boolean canMutateWorkspace() {
        return true;
    }

    @Override
    public String getToolName() {
        return "modifyFile";
    }

    @Override
    public String getDisplayName() {
        return "修改文件";
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
                "[工具调用] %s %s（变更已写入工作区，可在代码面板查看 Diff）",
                getDisplayName(), relativeFilePath
        );
    }
}
