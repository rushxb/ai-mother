package com.rush.rushaicodemother.ai.tools;

import cn.hutool.crypto.digest.DigestUtil;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.rush.rushaicodemother.infrastructure.filesystem.WorkspaceFileSystemService;
import com.rush.rushaicodemother.infrastructure.filesystem.WorkspaceFileSystemService.WorkspaceCopyResult;
import com.rush.rushaicodemother.infrastructure.filesystem.WorkspaceFileSystemService.WorkspaceDirectoryMetadata;
import com.rush.rushaicodemother.orchestration.artifact.ManualSnapshot;
import com.rush.rushaicodemother.orchestration.snapshot.GenerationSnapshotWorkspaceService;
import com.rush.rushaicodemother.orchestration.snapshot.SnapshotNamePolicy;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionPolicyException;
import com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskFenceGuard;
import com.rush.rushaicodemother.orchestration.tool.DestructiveToolAction;
import com.rush.rushaicodemother.orchestration.tool.GenerationToolExecutionContextService;
import com.rush.rushaicodemother.orchestration.tool.ToolApprovalService;
import com.rush.rushaicodemother.orchestration.tool.GenerationApprovalRequiredException;
import com.rush.rushaicodemother.orchestration.tool.ToolPublicFailureException;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolMemoryId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 快照与回滚工具
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SnapshotRollbackTool extends BaseTool implements ApprovalGatedTool {

    private final GenerationToolExecutionContextService toolExecutionContextService;
    private final ToolWorkspaceFileService workspaceFileService;
    private final WorkspaceFileSystemService workspaceFileSystemService;
    private final GenerationSnapshotWorkspaceService snapshotWorkspaceService;
    private final SnapshotNamePolicy snapshotNamePolicy;
    private final ToolApprovalService toolApprovalService;
    private final GenerationTaskFenceGuard generationTaskFenceGuard;

    /**
 * 返回{@code manage}快照。
 *
 * @param action 动作
 * @param snapshotName 快照名称
 * @param relativeProjectPath 项目相对路径
 * @param appId 应用编号
 * @return 处理后的快照回滚工具文本
 */
    @Tool("创建项目快照、列出快照、回滚到指定快照、删除快照。进行较大范围改动前建议先创建快照。")
    public String manageSnapshot(
            @P("操作类型：createSnapshot、listSnapshots、rollbackSnapshot、deleteSnapshot")
            String action,
            @P("快照名称。createSnapshot 可为空自动生成；rollbackSnapshot、deleteSnapshot 时必填")
            String snapshotName,
            @P("可选，相对项目子目录；为空则针对整个项目")
            String relativeProjectPath,
            @ToolMemoryId Long appId
    ) {
        String normalizedAction = StrUtil.blankToDefault(action, "listSnapshots");
        // 将可能失败的操作收敛在统一异常边界内，便于清理资源和转换错误。
        try {
            requireAppId(appId);
            String approvedInvocationId = requireApprovalIfNeeded(
                    appId, normalizedAction, snapshotName, relativeProjectPath);
            if (!"listSnapshots".equals(normalizedAction)) {
                assertCurrentTask(appId);
            }
            Path snapshotRoot = snapshotWorkspaceService.resolveApplicationRoot(appId);
            return switch (normalizedAction) {
                case "createSnapshot" -> {
                    String normalizedSnapshotName = snapshotNamePolicy.resolveOrCreate(snapshotName, "snapshot");
                    snapshotWorkspaceService.prepareApplicationRoot(appId);
                    yield createSnapshot(
                            resolveProjectPath(appId, relativeProjectPath),
                            normalizedSnapshotName,
                            appId
                    );
                }
                case "listSnapshots" -> listSnapshots(snapshotRoot);
                case "rollbackSnapshot" -> {
                    String normalizedSnapshotName = validateRequiredSnapshotName(snapshotName, "回滚时必须提供快照名称");
                    yield rollbackSnapshot(
                            appId,
                            resolveProjectPath(appId, relativeProjectPath),
                            normalizedSnapshotName,
                            approvedInvocationId
                    );
                }
                case "deleteSnapshot" -> deleteSnapshot(
                        appId,
                        validateRequiredSnapshotName(snapshotName, "删除时必须提供快照名称"),
                        approvedInvocationId
                );
                default -> throw toolFailure("错误：不支持的操作类型 - " + normalizedAction);
            };
        } catch (ToolPublicFailureException publicFailure) {
            throw publicFailure;
        } catch (ToolInputException e) {
            throw toolInputFailure("错误：", e);
        } catch (SnapshotNamePolicy.ValidationException e) {
            throw toolInputFailure("错误：", new ToolInputException(e.getMessage(), e));
        } catch (GenerationApprovalRequiredException approvalRequired) {
            throw approvalRequired;
        } catch (GenerationExecutionPolicyException executionPolicyFailure) {
            // 围栏失效和任务取消必须终止旧 worker，不能被转换成普通快照错误。
            throw executionPolicyFailure;
        } catch (Exception e) {
            log.error("管理快照失败，action: {}, snapshotName: {}, exceptionType: {}",
                    action, snapshotName, e.getClass().getSimpleName());
            throw toolFailure("管理快照失败，请稍后重试");
        }
    }

    /** 创建快照。 */
    private String createSnapshot(Path projectPath,
                                  String normalizedSnapshotName,
                                  Long appId) throws Exception {
        Path snapshotPath = snapshotWorkspaceService.resolveSnapshot(appId, normalizedSnapshotName);
        if (workspaceFileSystemService.isDirectory(snapshotPath)) {
            throw toolFailure("错误：快照名称已存在 - " + normalizedSnapshotName);
        }
        WorkspaceCopyResult copyResult = workspaceFileSystemService.copyDirectory(projectPath, snapshotPath);
        long fileCount = copyResult.fileCount();
        String taskId = toolExecutionContextService.getContext(appId)
                .map(context -> context.taskId())
                .orElse(null);
        ManualSnapshot artifact = new ManualSnapshot(
                "manual_snapshot",
                "SnapshotRollbackTool",
                "created",
                normalizedSnapshotName,
                appId,
                taskId,
                projectPath.toString(),
                snapshotPath.toString(),
                "ai_tool",
                fileCount,
                java.time.LocalDateTime.now()
        );
        return "快照创建成功: " + normalizedSnapshotName + "，文件数: " + fileCount
                + "\nartifact: " + cn.hutool.json.JSONUtil.toJsonStr(artifact.toPayload());
    }

    /** 列出符合条件的{@code Snapshots}。 */
    private String listSnapshots(Path snapshotRoot) throws Exception {
        if (!workspaceFileSystemService.isDirectory(snapshotRoot)) {
            return "当前没有可用快照";
        }
        List<WorkspaceDirectoryMetadata> snapshots = workspaceFileSystemService.listChildDirectories(snapshotRoot);
        if (snapshots.isEmpty()) {
            return "当前没有可用快照";
        }
        StringBuilder builder = new StringBuilder("可用快照:\n");
        for (WorkspaceDirectoryMetadata snapshot : snapshots) {
            builder.append("- ")
                    .append(snapshot.name())
                    .append(" | 修改时间: ")
                    .append(DateUtil.formatDateTime(DateUtil.date(snapshot.lastModifiedTime())))
                    .append('\n');
        }
        return builder.toString().trim();
    }

    /** 返回回滚快照。 */
    private String rollbackSnapshot(Long appId,
                                    Path projectPath,
                                    String normalizedSnapshotName,
                                    String invocationId) throws Exception {
        Path snapshotPath = snapshotWorkspaceService.resolveSnapshot(appId, normalizedSnapshotName);
        if (!workspaceFileSystemService.isDirectory(snapshotPath)) {
            throw toolFailure("错误：快照不存在 - " + normalizedSnapshotName);
        }
        String backupSnapshotName = snapshotNamePolicy.validateRequired(
                "pre_rollback_" + DigestUtil.sha256Hex(invocationId).substring(0, 16));
        Path backupSnapshotPath = snapshotWorkspaceService.resolveSnapshot(appId, backupSnapshotName);
        if (!workspaceFileSystemService.isDirectory(backupSnapshotPath)) {
            workspaceFileSystemService.copyDirectory(projectPath, backupSnapshotPath);
        }
        workspaceFileSystemService.replaceDirectory(snapshotPath, projectPath);
        return "已回滚到快照: " + normalizedSnapshotName + "，并自动备份当前版本为: " + backupSnapshotName;
    }

    /** 删除快照。 */
    private String deleteSnapshot(Long appId,
                                  String normalizedSnapshotName,
                                  String invocationId) throws Exception {
        Path snapshotPath = snapshotWorkspaceService.resolveSnapshot(appId, normalizedSnapshotName);
        if (!workspaceFileSystemService.isDirectory(snapshotPath)) {
            if (StrUtil.isNotBlank(invocationId)) {
                return "快照已由同一审批调用删除: " + normalizedSnapshotName;
            }
            throw toolFailure("错误：快照不存在 - " + normalizedSnapshotName);
        }
        workspaceFileSystemService.deleteDirectory(snapshotPath);
        return "已删除快照: " + normalizedSnapshotName;
    }

    private Path resolveProjectPath(Long appId, String relativeProjectPath) {
        return workspaceFileService.resolveDirectory(appId, relativeProjectPath).absolutePath();
    }

    private void assertCurrentTask(Long appId) {
        toolExecutionContextService.getContext(appId)
                .map(context -> context.taskId())
                .filter(StrUtil::isNotBlank)
                .ifPresent(generationTaskFenceGuard::assertCurrent);
    }

    private void requireAppId(Long appId) {
        if (appId == null || appId <= 0) {
            throw new ToolInputException("应用标识不能为空且必须为正数");
        }
    }

    private String validateRequiredSnapshotName(String snapshotName, String missingMessage) {
        if (StrUtil.isBlank(snapshotName)) {
            throw new ToolInputException(missingMessage);
        }
        return snapshotNamePolicy.validateRequired(snapshotName);
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
        String action = StrUtil.blankToDefault(arguments.getStr("action"), "listSnapshots");
        if (!"rollbackSnapshot".equals(action) && !"deleteSnapshot".equals(action)) {
            return;
        }
        try {
            requireApprovalIfNeeded(
                    appId,
                    action,
                    arguments.getStr("snapshotName"),
                    arguments.getStr("relativeProjectPath")
            );
        } catch (GenerationApprovalRequiredException approvalRequired) {
            throw approvalRequired;
        } catch (ToolInputException | SnapshotNamePolicy.ValidationException invalidInput) {
            // 工具方法渲染正常输入错误；这里不可能产生破坏性的副作用。
        } catch (Exception authorizationFailure) {
            throw new IllegalStateException("destructive snapshot authorization failed", authorizationFailure);
        }
    }

    /** 校验并返回有效的审批{@code If}{@code Needed}。 */
    private String requireApprovalIfNeeded(Long appId,
                                           String action,
                                           String snapshotName,
                                           String relativeProjectPath) throws Exception {
        DestructiveToolAction destructiveAction = switch (action) {
            case "rollbackSnapshot" -> DestructiveToolAction.SNAPSHOT_ROLLBACK;
            case "deleteSnapshot" -> DestructiveToolAction.SNAPSHOT_DELETE;
            default -> null;
        };
        if (destructiveAction == null) {
            return null;
        }
        String normalizedSnapshotName = snapshotNamePolicy.validateRequired(snapshotName);
        String taskId = toolExecutionContextService.getContext(appId)
                .map(context -> context.taskId())
                .orElse(null);
        if (taskId == null) {
            throw new ToolInputException("破坏性快照操作缺少生成任务上下文");
        }
        String normalizedProjectPath = normalizeApprovalProjectPath(relativeProjectPath);
        String approvalId = approvalId(
                appId, destructiveAction, normalizedSnapshotName, normalizedProjectPath);
        GenerationToolExecutionContextService.ToolInvocationExecution invocation =
                toolExecutionContextService.currentInvocation().orElse(null);
        if (!toolApprovalService.isExecutionAuthorized(
                taskId, destructiveAction, approvalId, invocation)) {
            Path snapshotPath = snapshotWorkspaceService.resolveSnapshot(appId, normalizedSnapshotName);
            if (!workspaceFileSystemService.isDirectory(snapshotPath)) {
                throw new ToolInputException("快照不存在 - " + normalizedSnapshotName);
            }
            throw new GenerationApprovalRequiredException(
                    taskId,
                    destructiveAction,
                    approvalId,
                    Map.of(
                            "appId", appId,
                            "snapshotName", normalizedSnapshotName,
                            "relativeProjectPath", normalizedProjectPath,
                            "action", destructiveAction.value()
                    )
            );
        }
        return invocation.requestId();
    }

    private String approvalId(Long appId,
                              DestructiveToolAction action,
                              String normalizedSnapshotName,
                              String normalizedProjectPath) {
        return DigestUtil.sha256Hex(appId + ":" + action.name() + ":"
                + normalizedSnapshotName + ":" + normalizedProjectPath);
    }

    private String normalizeApprovalProjectPath(String relativeProjectPath) {
        if (StrUtil.isBlank(relativeProjectPath)) {
            return "";
        }
        return relativeProjectPath.trim().replace('\\', '/');
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
        return "manageSnapshot";
    }

    @Override
    public String getDisplayName() {
        return "快照回滚";
    }

    /**
 * 将工具执行结果整理为模型可消费的文本。
 *
 * @param arguments 参数
 * @return 处理后的方法执行结果文本
 */
    @Override
    public String generateToolExecutedResult(JSONObject arguments) {
        return String.format("[工具调用] %s %s %s",
                getDisplayName(),
                arguments.getStr("action"),
                StrUtil.blankToDefault(arguments.getStr("snapshotName"), ""));
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
        return generateToolExecutedResult(arguments) + "\n" + summarizeResult(toolResult, 240);
    }

    private String summarizeResult(String toolResult, int maxChars) {
        if (StrUtil.isBlank(toolResult)) {
            return "";
        }
        String normalized = toolResult.replace("\r", " ").replace("\n", " ").trim();
        return StrUtil.sub(normalized, 0, Math.min(normalized.length(), maxChars));
    }
}
