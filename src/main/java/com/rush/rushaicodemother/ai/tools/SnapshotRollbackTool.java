package com.rush.rushaicodemother.ai.tools;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import com.rush.rushaicodemother.constant.AppConstant;
import com.rush.rushaicodemother.orchestration.artifact.ManualSnapshot;
import com.rush.rushaicodemother.orchestration.tool.GenerationToolExecutionContextService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;

/**
 * 快照与回滚工具
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SnapshotRollbackTool extends BaseTool {

    private static final DateTimeFormatter SNAPSHOT_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private final GenerationToolExecutionContextService toolExecutionContextService;

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
            Path projectPath = ToolPathSupport.resolvePath(relativeProjectPath, appId);
            if (!Files.exists(projectPath) || !Files.isDirectory(projectPath)) {
                return "错误：项目目录不存在 - " + relativeProjectPath;
            }
            Path snapshotRoot = resolveSnapshotRoot(appId);
            Files.createDirectories(snapshotRoot);
            return switch (normalizedAction) {
                case "createSnapshot" -> createSnapshot(projectPath, snapshotRoot, snapshotName, appId);
                case "listSnapshots" -> listSnapshots(snapshotRoot);
                case "rollbackSnapshot" -> rollbackSnapshot(projectPath, snapshotRoot, snapshotName);
                case "deleteSnapshot" -> deleteSnapshot(snapshotRoot, snapshotName);
                default -> "错误：不支持的操作类型 - " + normalizedAction;
            };
        } catch (IllegalArgumentException e) {
            return "错误：" + e.getMessage();
        } catch (Exception e) {
            log.error("管理快照失败，action: {}, snapshotName: {}", action, snapshotName, e);
            return "管理快照失败: " + e.getMessage();
        }
    }

    private String createSnapshot(Path projectPath, Path snapshotRoot, String snapshotName, Long appId) throws Exception {
        String normalizedSnapshotName = normalizeSnapshotName(snapshotName);
        Path snapshotPath = snapshotRoot.resolve(normalizedSnapshotName);
        if (Files.exists(snapshotPath)) {
            return "错误：快照名称已存在 - " + normalizedSnapshotName;
        }
        ProjectWorkspaceSupport.copyProject(projectPath, snapshotPath);
        long fileCount = ProjectWorkspaceSupport.listProjectFiles(snapshotPath).size();
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
        if (!Files.exists(snapshotRoot)) {
            return "当前没有可用快照";
        }
        try (var stream = Files.list(snapshotRoot)) {
            List<Path> snapshots = stream
                    .filter(Files::isDirectory)
                    .sorted(Comparator.comparingLong(this::lastModifiedTime).reversed())
                    .toList();
            if (snapshots.isEmpty()) {
                return "当前没有可用快照";
            }
            StringBuilder builder = new StringBuilder("可用快照:\n");
            for (Path snapshot : snapshots) {
                builder.append("- ")
                        .append(snapshot.getFileName())
                        .append(" | 修改时间: ")
                        .append(DateUtil.formatDateTime(DateUtil.date(new File(snapshot.toString()).lastModified())))
                        .append('\n');
            }
            return builder.toString().trim();
        }
    }

    private String rollbackSnapshot(Path projectPath, Path snapshotRoot, String snapshotName) throws Exception {
        if (StrUtil.isBlank(snapshotName)) {
            return "错误：回滚时必须提供快照名称";
        }
        Path snapshotPath = snapshotRoot.resolve(snapshotName).normalize();
        ToolPathSupport.ensureWithinProject(snapshotRoot, snapshotPath);
        if (!Files.exists(snapshotPath) || !Files.isDirectory(snapshotPath)) {
            return "错误：快照不存在 - " + snapshotName;
        }
        String backupSnapshotName = "pre_rollback_" + java.time.LocalDateTime.now().format(SNAPSHOT_TIME_FORMATTER);
        Path backupSnapshotPath = snapshotRoot.resolve(backupSnapshotName);
        ProjectWorkspaceSupport.copyProject(projectPath, backupSnapshotPath);
        ProjectWorkspaceSupport.cleanDirectory(projectPath);
        ProjectWorkspaceSupport.copyProject(snapshotPath, projectPath);
        return "已回滚到快照: " + snapshotName + "，并自动备份当前版本为: " + backupSnapshotName;
    }

    private String deleteSnapshot(Path snapshotRoot, String snapshotName) {
        if (StrUtil.isBlank(snapshotName)) {
            return "错误：删除时必须提供快照名称";
        }
        Path snapshotPath = snapshotRoot.resolve(snapshotName).normalize();
        ToolPathSupport.ensureWithinProject(snapshotRoot, snapshotPath);
        if (!Files.exists(snapshotPath) || !Files.isDirectory(snapshotPath)) {
            return "错误：快照不存在 - " + snapshotName;
        }
        FileUtil.del(snapshotPath.toFile());
        return "已删除快照: " + snapshotName;
    }

    private String normalizeSnapshotName(String snapshotName) {
        if (StrUtil.isBlank(snapshotName)) {
            return "snapshot_" + java.time.LocalDateTime.now().format(SNAPSHOT_TIME_FORMATTER);
        }
        String normalized = snapshotName.trim().replaceAll("[^a-zA-Z0-9_-]", "_");
        if (StrUtil.isBlank(normalized)) {
            throw new IllegalArgumentException("快照名称不能为空");
        }
        return normalized;
    }

    private Path resolveSnapshotRoot(Long appId) {
        return Path.of(AppConstant.CODE_SNAPSHOT_ROOT_DIR, String.valueOf(appId));
    }

    private long lastModifiedTime(Path path) {
        return path.toFile().lastModified();
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
