package com.rush.rushaicodemother.ai.tools;

import cn.hutool.crypto.digest.DigestUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.rush.rushaicodemother.infrastructure.filesystem.WorkspaceFileSystemException;
import com.rush.rushaicodemother.orchestration.artifact.ManualSnapshot;
import com.rush.rushaicodemother.orchestration.snapshot.GenerationSnapshotWorkspaceService;
import com.rush.rushaicodemother.orchestration.snapshot.SnapshotCapture;
import com.rush.rushaicodemother.orchestration.snapshot.SnapshotKind;
import com.rush.rushaicodemother.orchestration.snapshot.SnapshotNamePolicy;
import com.rush.rushaicodemother.orchestration.snapshot.SnapshotScope;
import com.rush.rushaicodemother.orchestration.snapshot.SnapshotSelector;
import com.rush.rushaicodemother.orchestration.snapshot.SnapshotStoreException;
import com.rush.rushaicodemother.orchestration.snapshot.StoredSnapshot;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionPolicyException;
import com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskFenceGuard;
import com.rush.rushaicodemother.orchestration.tool.DestructiveToolAction;
import com.rush.rushaicodemother.orchestration.tool.GenerationToolExecutionContextService;
import com.rush.rushaicodemother.orchestration.tool.GenerationToolExecutionContext;
import com.rush.rushaicodemother.orchestration.tool.ToolApprovalService;
import com.rush.rushaicodemother.orchestration.tool.GenerationApprovalRequiredException;
import com.rush.rushaicodemother.orchestration.tool.ToolPublicFailureException;
import com.rush.rushaicodemother.orchestration.tool.ToolResultEvidence;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolMemoryId;
import dev.langchain4j.data.message.TextContent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * 快照与回滚工具
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SnapshotRollbackTool extends BaseTool implements ApprovalGatedTool {

    private static final DateTimeFormatter SNAPSHOT_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final GenerationToolExecutionContextService toolExecutionContextService;
    private final ToolWorkspaceFileService workspaceFileService;
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
    public TextContent manageSnapshot(
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
            ApprovedSnapshotInvocation approvedInvocation = requireApprovalIfNeeded(
                    appId, normalizedAction, snapshotName, relativeProjectPath);
            if (!"listSnapshots".equals(normalizedAction)) {
                assertCurrentTask(appId);
            }
            return switch (normalizedAction) {
                case "createSnapshot" -> {
                    String normalizedSnapshotName = snapshotNamePolicy.resolveOrCreate(snapshotName, "snapshot");
                    yield TextContent.from(createSnapshot(
                            resolveProjectPath(appId, relativeProjectPath),
                            normalizedSnapshotName,
                            appId,
                            relativeProjectPath
                    ));
                }
                case "listSnapshots" -> TextContent.from(listSnapshots(appId));
                case "rollbackSnapshot" -> {
                    yield rollbackSnapshot(
                            appId,
                            resolveProjectPath(appId, relativeProjectPath),
                            approvedInvocation
                    );
                }
                case "deleteSnapshot" -> TextContent.from(deleteSnapshot(
                        approvedInvocation
                ));
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
        } catch (SnapshotStoreException snapshotFailure) {
            if (snapshotFailure.reason() == SnapshotStoreException.Reason.NOT_FOUND) {
                throw toolFailure("错误：快照不存在 - "
                        + StrUtil.blankToDefault(snapshotName, "(未提供)"));
            }
            if (snapshotFailure.reason() == SnapshotStoreException.Reason.ALREADY_EXISTS) {
                throw toolFailure("错误：快照名称已存在 - "
                        + StrUtil.blankToDefault(snapshotName, "(未提供)"));
            }
            throw toolFailure("快照校验失败，未执行任何破坏性操作");
        } catch (Exception e) {
            log.error("管理快照失败，action: {}, snapshotName: {}, exceptionType: {}",
                    action, snapshotName, e.getClass().getSimpleName());
            throw toolFailure("管理快照失败，请稍后重试");
        }
    }

    /** 创建快照。 */
    private String createSnapshot(Path projectPath,
                                  String normalizedSnapshotName,
                                  Long appId,
                                  String relativeProjectPath) throws Exception {
        GenerationToolExecutionContext context = requireSnapshotContext(appId);
        SnapshotScope scope = snapshotScope(context, relativeProjectPath);
        StoredSnapshot snapshot = snapshotWorkspaceService.capture(
                new SnapshotCapture(
                        normalizedSnapshotName,
                        scope,
                        projectPath,
                        SnapshotKind.MANUAL,
                        context.taskId(),
                        context.executionFence().executionEpoch()
                ),
                () -> assertCurrentTask(appId)
        );
        ManualSnapshot artifact = new ManualSnapshot(
                "manual_snapshot",
                "SnapshotRollbackTool",
                "created",
                snapshot.snapshotName(),
                snapshot.snapshotId(),
                snapshot.manifestSha256(),
                snapshot.scope().workspaceType().getValue(),
                snapshot.scope().relativePath(),
                snapshot.creatorExecutionEpoch(),
                appId,
                context.taskId(),
                projectPath.toString(),
                snapshot.containerPath().toString(),
                "ai_tool",
                snapshot.fingerprint().fileCount(),
                java.time.LocalDateTime.now()
        );
        return "快照创建成功: " + normalizedSnapshotName
                + "，快照ID: " + snapshot.snapshotId()
                + "，文件数: " + snapshot.fingerprint().fileCount()
                + "\nartifact: " + cn.hutool.json.JSONUtil.toJsonStr(artifact.toPayload());
    }

    /** 列出符合条件的{@code Snapshots}。 */
    private String listSnapshots(Long appId) throws Exception {
        java.util.List<StoredSnapshot> snapshots = snapshotWorkspaceService.listSnapshots(appId);
        if (snapshots.isEmpty()) {
            return "当前没有可用快照";
        }
        StringBuilder builder = new StringBuilder("可用快照:\n");
        for (StoredSnapshot snapshot : snapshots) {
            builder.append("- ")
                    .append(snapshot.snapshotName())
                    .append(" | ID: ")
                    .append(snapshot.snapshotId())
                    .append(" | 工程类型: ")
                    .append(snapshot.scope().workspaceType().getValue())
                    .append(" | scope: ")
                    .append(snapshot.scope().relativePath())
                    .append(" | 创建时间: ")
                    .append(SNAPSHOT_TIME_FORMAT.format(
                            snapshot.createdAt().atZone(ZoneId.systemDefault())))
                    .append('\n');
        }
        return builder.toString().trim();
    }

    /** 返回回滚快照。 */
    private TextContent rollbackSnapshot(Long appId,
                                         Path projectPath,
                                         ApprovedSnapshotInvocation approvedInvocation) throws Exception {
        requireApprovedInvocation(approvedInvocation);
        StoredSnapshot sourceSnapshot = snapshotWorkspaceService.requireSnapshot(
                approvedInvocation.selector());
        GenerationToolExecutionContext context = requireSnapshotContext(appId);
        String backupSnapshotName = snapshotNamePolicy.validateRequired(
                "pre_rollback_" + DigestUtil.sha256Hex(approvedInvocation.invocationId()).substring(0, 16));
        Runnable continuationCheck = () -> assertCurrentTask(appId);
        StoredSnapshot backupSnapshot = snapshotWorkspaceService.captureOrReuse(
                new SnapshotCapture(
                        backupSnapshotName,
                        sourceSnapshot.scope(),
                        projectPath,
                        SnapshotKind.PRE_ROLLBACK_BACKUP,
                        context.taskId(),
                        context.executionFence().executionEpoch()
                ),
                continuationCheck
        );
        // 备份可能耗时，覆盖项目之前必须再次确认执行权，禁止旧 worker 回滚新 owner 的结果。
        assertCurrentTask(appId);
        try {
            snapshotWorkspaceService.restore(
                    SnapshotSelector.exact(sourceSnapshot),
                    projectPath,
                    continuationCheck
            );
        } catch (WorkspaceFileSystemException exception) {
            if (exception.reason() != WorkspaceFileSystemException.Reason.REPLACE_OUTCOME_UNKNOWN) {
                throw exception;
            }
            // 物理结果未知时必须以正常工具结果落实审批终态，禁止同一审批被盲目重放。
            return ToolResultEvidence.workspaceInvalidated(
                    "回滚结果无法确认：工作区目录交换和原版本恢复均未得到可靠结果。"
                            + "请勿自动重试，必须重新读取工作区并人工核对保留的 previous 目录。"
            );
        }
        return ToolResultEvidence.workspaceInvalidated(
                "已回滚到快照: " + sourceSnapshot.snapshotName()
                        + "（" + sourceSnapshot.snapshotId() + "）"
                        + "，并自动备份当前版本为: " + backupSnapshot.snapshotName()
                        + "（" + backupSnapshot.snapshotId() + "）"
                        + "。回滚前的文件读取、搜索和变更事实均已失效，继续操作前请重新读取目标文件。"
        );
    }

    /** 删除快照。 */
    private String deleteSnapshot(ApprovedSnapshotInvocation approvedInvocation) throws Exception {
        requireApprovedInvocation(approvedInvocation);
        StoredSnapshot snapshot = snapshotWorkspaceService.requireSnapshot(approvedInvocation.selector());
        snapshotWorkspaceService.deleteSnapshot(
                SnapshotSelector.exact(snapshot),
                () -> assertCurrentTask(snapshot.scope().appId())
        );
        return "已删除快照: " + snapshot.snapshotName() + "（" + snapshot.snapshotId() + "）";
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

    private GenerationToolExecutionContext requireSnapshotContext(Long appId) {
        GenerationToolExecutionContext context = toolExecutionContextService.getContext(appId)
                .orElseThrow(() -> new ToolInputException("快照操作缺少生成任务上下文"));
        if (StrUtil.isBlank(context.taskId())
                || context.codeGenType() == null
                || context.executionFence() == null) {
            throw new ToolInputException("快照操作缺少工程类型或执行纪元");
        }
        if (!context.taskId().equals(context.executionFence().taskId())) {
            throw new ToolInputException("快照操作的任务与执行纪元不一致");
        }
        return context;
    }

    private SnapshotScope snapshotScope(GenerationToolExecutionContext context,
                                        String relativeProjectPath) {
        return new SnapshotScope(
                context.appId(),
                context.codeGenType(),
                SnapshotScope.normalizeRelativePath(relativeProjectPath)
        );
    }

    private void requireApprovedInvocation(ApprovedSnapshotInvocation approvedInvocation) {
        if (approvedInvocation == null
                || StrUtil.isBlank(approvedInvocation.invocationId())
                || approvedInvocation.selector() == null) {
            throw new ToolInputException("破坏性快照操作缺少有效审批上下文");
        }
    }

    private void requireAppId(Long appId) {
        if (appId == null || appId <= 0) {
            throw new ToolInputException("应用标识不能为空且必须为正数");
        }
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
        } catch (ToolInputException | SnapshotNamePolicy.ValidationException | SnapshotStoreException invalidInput) {
            // 工具方法渲染正常输入错误；这里不可能产生破坏性的副作用。
        } catch (Exception authorizationFailure) {
            throw new IllegalStateException("destructive snapshot authorization failed", authorizationFailure);
        }
    }

    /** 校验并返回有效的审批{@code If}{@code Needed}。 */
    private ApprovedSnapshotInvocation requireApprovalIfNeeded(Long appId,
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
        GenerationToolExecutionContext context = requireSnapshotContext(appId);
        String taskId = context.taskId();
        SnapshotScope scope = snapshotScope(context, relativeProjectPath);
        StoredSnapshot snapshot = snapshotWorkspaceService.requireSnapshot(
                SnapshotSelector.forWorkspace(normalizedSnapshotName, scope));
        SnapshotSelector exactSelector = SnapshotSelector.exact(snapshot);
        String approvalId = approvalId(
                appId,
                destructiveAction,
                snapshot
        );
        GenerationToolExecutionContextService.ToolInvocationExecution invocation =
                toolExecutionContextService.currentInvocation().orElse(null);
        if (!toolApprovalService.isExecutionAuthorized(
                taskId, destructiveAction, approvalId, invocation)) {
            throw new GenerationApprovalRequiredException(
                    taskId,
                    destructiveAction,
                    approvalId,
                    Map.of(
                            "appId", appId,
                            "snapshotName", normalizedSnapshotName,
                            "snapshotId", snapshot.snapshotId(),
                            "manifestSha256", snapshot.manifestSha256(),
                            "relativeProjectPath", scope.relativePath(),
                            "action", destructiveAction.value()
                    )
            );
        }
        if (invocation == null) {
            throw new ToolInputException("破坏性快照操作缺少工具调用身份");
        }
        return new ApprovedSnapshotInvocation(invocation.requestId(), exactSelector);
    }

    private String approvalId(Long appId,
                              DestructiveToolAction action,
                              StoredSnapshot snapshot) {
        return DigestUtil.sha256Hex(appId + ":" + action.name() + ":"
                + snapshot.snapshotId() + ":"
                + snapshot.manifestSha256() + ":"
                + snapshot.scope().workspaceType().getValue() + ":"
                + snapshot.scope().relativePath());
    }

    private record ApprovedSnapshotInvocation(String invocationId, SnapshotSelector selector) {
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
