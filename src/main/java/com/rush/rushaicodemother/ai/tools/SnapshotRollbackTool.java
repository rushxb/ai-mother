package com.rush.rushaicodemother.ai.tools;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import com.rush.rushaicodemother.constant.AppConstant;
import com.rush.rushaicodemother.infrastructure.filesystem.WorkspaceFileSystemService;
import com.rush.rushaicodemother.infrastructure.filesystem.WorkspaceFileSystemService.WorkspaceCopyResult;
import com.rush.rushaicodemother.infrastructure.filesystem.WorkspaceFileSystemService.WorkspaceDirectoryMetadata;
import com.rush.rushaicodemother.orchestration.artifact.ManualSnapshot;
import com.rush.rushaicodemother.orchestration.snapshot.SnapshotNamePolicy;
import com.rush.rushaicodemother.orchestration.tool.GenerationToolExecutionContextService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;

/**
 * 快照与回滚工具
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SnapshotRollbackTool extends BaseTool {

    private final GenerationToolExecutionContextService toolExecutionContextService;
    private final ToolWorkspaceFileService workspaceFileService;
    private final WorkspaceFileSystemService workspaceFileSystemService;
    private final SnapshotNamePolicy snapshotNamePolicy;

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
        try {
            requireAppId(appId);
            Path snapshotRoot = resolveSnapshotRoot(appId);
            return switch (normalizedAction) {
                case "createSnapshot" -> {
                    String normalizedSnapshotName = snapshotNamePolicy.resolveOrCreate(snapshotName, "snapshot");
                    workspaceFileSystemService.ensureDirectory(snapshotRoot);
                    yield createSnapshot(
                            resolveProjectPath(appId, relativeProjectPath),
                            snapshotRoot,
                            normalizedSnapshotName,
                            appId
                    );
                }
                case "listSnapshots" -> listSnapshots(snapshotRoot);
                case "rollbackSnapshot" -> {
                    String normalizedSnapshotName = validateRequiredSnapshotName(snapshotName, "回滚时必须提供快照名称");
                    yield rollbackSnapshot(
                            resolveProjectPath(appId, relativeProjectPath),
                            snapshotRoot,
                            normalizedSnapshotName
                    );
                }
                case "deleteSnapshot" -> deleteSnapshot(
                        snapshotRoot,
                        validateRequiredSnapshotName(snapshotName, "删除时必须提供快照名称")
                );
                default -> "错误：不支持的操作类型 - " + normalizedAction;
            };
        } catch (ToolInputException e) {
            return renderInputError(e);
        } catch (SnapshotNamePolicy.ValidationException e) {
            return renderInputError(new ToolInputException(e.getMessage(), e));
        } catch (Exception e) {
            log.error("管理快照失败，action: {}, snapshotName: {}, exceptionType: {}",
                    action, snapshotName, e.getClass().getSimpleName());
            return "管理快照失败，请稍后重试";
        }
    }

    private String createSnapshot(Path projectPath,
                                  Path snapshotRoot,
                                  String normalizedSnapshotName,
                                  Long appId) throws Exception {
        Path snapshotPath = snapshotRoot.resolve(normalizedSnapshotName);
        if (workspaceFileSystemService.isDirectory(snapshotPath)) {
            return "错误：快照名称已存在 - " + normalizedSnapshotName;
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

    private String rollbackSnapshot(Path projectPath,
                                    Path snapshotRoot,
                                    String normalizedSnapshotName) throws Exception {
        Path snapshotPath = snapshotRoot.resolve(normalizedSnapshotName);
        if (!workspaceFileSystemService.isDirectory(snapshotPath)) {
            return "错误：快照不存在 - " + normalizedSnapshotName;
        }
        String backupSnapshotName = snapshotNamePolicy.createAutomaticName("pre_rollback");
        Path backupSnapshotPath = snapshotRoot.resolve(backupSnapshotName);
        workspaceFileSystemService.copyDirectory(projectPath, backupSnapshotPath);
        workspaceFileSystemService.replaceDirectory(snapshotPath, projectPath);
        return "已回滚到快照: " + normalizedSnapshotName + "，并自动备份当前版本为: " + backupSnapshotName;
    }

    private String deleteSnapshot(Path snapshotRoot, String normalizedSnapshotName) throws Exception {
        Path snapshotPath = snapshotRoot.resolve(normalizedSnapshotName);
        if (!workspaceFileSystemService.isDirectory(snapshotPath)) {
            return "错误：快照不存在 - " + normalizedSnapshotName;
        }
        workspaceFileSystemService.deleteDirectory(snapshotPath);
        return "已删除快照: " + normalizedSnapshotName;
    }

    private Path resolveSnapshotRoot(Long appId) {
        return Path.of(AppConstant.CODE_SNAPSHOT_ROOT_DIR, String.valueOf(appId));
    }

    private Path resolveProjectPath(Long appId, String relativeProjectPath) {
        return workspaceFileService.resolveDirectory(appId, relativeProjectPath).absolutePath();
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

    @Override
    public String getToolName() {
        return "manageSnapshot";
    }

    @Override
    public String getDisplayName() {
        return "快照回滚";
    }

    @Override
    public String generateToolExecutedResult(JSONObject arguments) {
        return String.format("[工具调用] %s %s %s",
                getDisplayName(),
                arguments.getStr("action"),
                StrUtil.blankToDefault(arguments.getStr("snapshotName"), ""));
    }

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
