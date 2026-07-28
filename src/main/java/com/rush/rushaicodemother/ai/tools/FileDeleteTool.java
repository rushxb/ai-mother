package com.rush.rushaicodemother.ai.tools;

import com.rush.rushaicodemother.infrastructure.diagnostic.LogExceptionSanitizer;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import lombok.extern.slf4j.Slf4j;
import com.rush.rushaicodemother.orchestration.artifact.PatchApplyResult;
import com.rush.rushaicodemother.orchestration.patch.PatchOperation;
import com.rush.rushaicodemother.orchestration.tool.DestructiveToolAction;
import com.rush.rushaicodemother.orchestration.tool.GenerationApprovalRequiredException;
import com.rush.rushaicodemother.orchestration.tool.GenerationToolExecutionContextService;
import com.rush.rushaicodemother.orchestration.tool.ToolApprovalService;
import com.rush.rushaicodemother.orchestration.tool.ToolExecutionGateway;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionPolicyException;
import cn.hutool.crypto.digest.DigestUtil;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/**
 * 文件删除工具
 * 支持 AI 通过工具调用的方式删除文件
 */
@Slf4j
@Component
public class FileDeleteTool extends BaseTool implements ApprovalGatedTool {

    private final ToolExecutionGateway toolExecutionGateway;
    private final ToolWorkspaceFileService workspaceFileService;
    private final GenerationToolExecutionContextService toolExecutionContextService;
    private final ToolApprovalService toolApprovalService;

    public FileDeleteTool(
            ToolExecutionGateway toolExecutionGateway,
            ToolWorkspaceFileService workspaceFileService,
            GenerationToolExecutionContextService toolExecutionContextService,
            ToolApprovalService toolApprovalService
    ) {
        this.toolExecutionGateway = toolExecutionGateway;
        this.workspaceFileService = workspaceFileService;
        this.toolExecutionContextService = toolExecutionContextService;
        this.toolApprovalService = toolApprovalService;
    }

    /**
 * 删除文件。
 *
 * @param relativeFilePath {@code relativeFilePath} 对应的调用参数
 * @param appId 应用编号
 * @return 处理后的文件文本
 */
    @Tool("删除指定路径的文件")
    public String deleteFile(
            @P("文件的相对路径")
            String relativeFilePath,
            @ToolMemoryId Long appId
    ) {
        // 将可能失败的操作收敛在统一异常边界内，便于清理资源和转换错误。
        try {
            ToolWorkspaceFileService.ToolWorkspaceFile file =
                    workspaceFileService.resolveFile(appId, relativeFilePath);
            String normalizedPath = file.relativePath();
            if (!workspaceFileService.exists(file)) {
                return "警告：文件不存在，无需删除 - " + normalizedPath;
            }
            if (!workspaceFileService.isRegularFile(file)) {
                return "错误：指定路径不是文件，无法删除 - " + normalizedPath;
            }
            // 安全检查：避免删除重要文件
            String fileName = file.fileName();
            if (isImportantFile(fileName)) {
                return "错误：不允许删除重要文件 - " + fileName;
            }
            requireApproval(appId, normalizedPath);
            PatchApplyResult result = applyWithGlobalChangePlan(
                    appId,
                    file.projectRoot(),
                    PatchOperation.delete(normalizedPath)
            );
            if ("applied".equals(result.status())) {
                log.info("成功删除文件: {}", file.absolutePath());
                return "文件删除成功: " + normalizedPath;
            }
            return "删除文件失败: " + normalizedPath + ", 原因: " + result.reason();
        } catch (ToolInputException e) {
            return renderInputError("删除文件失败: ", e);
        } catch (GenerationApprovalRequiredException approvalRequired) {
            throw approvalRequired;
        } catch (GenerationExecutionPolicyException policyFailure) {
            throw policyFailure;
        } catch (Exception e) {
            log.error("删除文件失败，relativeFilePath: {}", relativeFilePath, LogExceptionSanitizer.sanitize(e));
            return "删除文件失败，请稍后重试";
        }
    }

    private PatchApplyResult applyWithGlobalChangePlan(Long appId, Path projectRoot, PatchOperation operation) {
        return toolExecutionGateway.applyPatch(appId, projectRoot, operation, "tool-delete-file", "delete_file");
    }

    /**
 * 处理授权调用。
 *
 * @param request 请求参数
 * @param appId 应用编号
 */
    @Override
    public void authorizeInvocation(ToolExecutionRequest request, Long appId) {
        if (request == null || request.arguments() == null || request.arguments().isBlank()) {
            return;
        }
        JSONObject arguments;
        try {
            arguments = JSONUtil.parseObj(request.arguments());
        } catch (RuntimeException malformedArguments) {
            return;
        }
        String relativeFilePath = arguments.getStr("relativeFilePath");
        if (relativeFilePath == null || relativeFilePath.isBlank()) {
            return;
        }
        try {
            ToolWorkspaceFileService.ToolWorkspaceFile file =
                    workspaceFileService.resolveFile(appId, relativeFilePath);
            if (!workspaceFileService.exists(file)
                    || !workspaceFileService.isRegularFile(file)
                    || isImportantFile(file.fileName())) {
                return;
            }
            requireApproval(appId, file.relativePath());
        } catch (ToolInputException invalidInput) {
            // 工具方法渲染正常输入错误；这里不可能产生破坏性的副作用。
        }
    }

    /** 校验并返回有效的审批。 */
    private void requireApproval(Long appId, String normalizedPath) {
        String taskId = toolExecutionContextService.getContext(appId)
                .map(context -> context.taskId())
                .orElse(null);
        if (taskId == null || taskId.isBlank()) {
            throw new ToolInputException("破坏性文件操作缺少生成任务上下文");
        }
        DestructiveToolAction action = DestructiveToolAction.FILE_DELETE;
        String approvalId = DigestUtil.sha256Hex(
                appId + ":" + action.name() + ":" + normalizedPath);
        GenerationToolExecutionContextService.ToolInvocationExecution invocation =
                toolExecutionContextService.currentInvocation().orElse(null);
        if (!toolApprovalService.isExecutionAuthorized(taskId, action, approvalId, invocation)) {
            throw new GenerationApprovalRequiredException(
                    taskId,
                    action,
                    approvalId,
                    java.util.Map.of(
                            "appId", appId,
                            "relativeFilePath", normalizedPath,
                            "action", action.value()
                    )
            );
        }
    }

    /**
     * 判断是否是重要文件，不允许删除
     */
    private boolean isImportantFile(String fileName) {
        String[] importantFiles = {
                "package.json", "package-lock.json", "yarn.lock", "pnpm-lock.yaml",
                "vite.config.js", "vite.config.ts", "vue.config.js",
                "tsconfig.json", "tsconfig.app.json", "tsconfig.node.json",
                "index.html", "main.js", "main.ts", "App.vue", ".gitignore", "README.md"
        };
        for (String important : importantFiles) {
            if (important.equalsIgnoreCase(fileName)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public ToolRiskLevel getRiskLevel() {
        return ToolRiskLevel.DESTRUCTIVE;
    }

    @Override
    public boolean canMutateWorkspace() {
        return true;
    }

    @Override
    public String getToolName() {
        return "deleteFile";
    }

    @Override
    public String getDisplayName() {
        return "删除文件";
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
        return String.format(" [工具调用] %s %s", getDisplayName(), relativeFilePath);
    }
}
