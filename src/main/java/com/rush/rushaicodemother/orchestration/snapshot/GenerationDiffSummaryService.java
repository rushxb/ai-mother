package com.rush.rushaicodemother.orchestration.snapshot;

import cn.hutool.core.io.FileUtil;
import com.rush.rushaicodemother.infrastructure.filesystem.WorkspaceFileSystemException;
import com.rush.rushaicodemother.infrastructure.filesystem.WorkspaceFileSystemService;
import com.rush.rushaicodemother.infrastructure.filesystem.WorkspaceFileSystemService.WorkspaceFileMetadata;
import com.rush.rushaicodemother.infrastructure.filesystem.WorkspaceFileSystemService.WorkspaceScan;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.artifact.DiffSummary;
import com.rush.rushaicodemother.orchestration.artifact.GenerationArtifact;
import com.rush.rushaicodemother.orchestration.artifact.RollbackPoint;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContextService;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionPolicyException;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspaceService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 根据生成前回滚点生成非破坏性的生成后差异摘要。
 */
@Slf4j
@Component
public class GenerationDiffSummaryService {

    private static final int MAX_FILES = 40;
    private static final long MAX_DIFF_TEXT_FILE_BYTES = 2L * 1024 * 1024;
    private static final Set<String> TEXT_EXTENSIONS = Set.of(
            "vue", "js", "ts", "jsx", "tsx", "json", "css", "scss", "less", "html", "md", "txt", "yml", "yaml"
    );

    private final GenerationWorkspaceService generationWorkspaceService;
    private final GenerationSnapshotWorkspaceService snapshotWorkspaceService;
    private final WorkspaceFileSystemService workspaceFileSystemService;
    private final GenerationExecutionContextService executionContextService;

    /**
 * 创建生成{@code Diff}汇总服务实例并完成必要的依赖和初始状态设置。
 *
 * @param generationWorkspaceService 生成工作区服务
 * @param snapshotWorkspaceService 快照工作区服务
 * @param workspaceFileSystemService 处理该职责的领域服务
 */
    public GenerationDiffSummaryService(
            GenerationWorkspaceService generationWorkspaceService,
            GenerationSnapshotWorkspaceService snapshotWorkspaceService,
            WorkspaceFileSystemService workspaceFileSystemService,
            GenerationExecutionContextService executionContextService
    ) {
        this.generationWorkspaceService = java.util.Objects.requireNonNull(
                generationWorkspaceService,
                "generationWorkspaceService must not be null"
        );
        this.snapshotWorkspaceService = java.util.Objects.requireNonNull(
                snapshotWorkspaceService,
                "snapshotWorkspaceService must not be null"
        );
        this.workspaceFileSystemService = java.util.Objects.requireNonNull(
                workspaceFileSystemService,
                "workspaceFileSystemService must not be null"
        );
        this.executionContextService = java.util.Objects.requireNonNull(
                executionContextService,
                "executionContextService must not be null"
        );
    }

    /**
 * 计算{@code marize}的汇总值。
 *
 * @param appId 应用编号
 * @param targetType 目标类型
 * @param taskId 任务编号
 * @param rollbackPointArtifact 回滚点制品
 * @return {@code marize}
 */
    public DiffSummary summarize(Long appId,
                                  CodeGenTypeEnum targetType,
                                  String taskId,
                                  GenerationArtifact rollbackPointArtifact) {
        if (appId == null || appId <= 0 || targetType == null) {
            return DiffSummary.skipped(appId, taskId, "", "", "invalid_generation_context");
        }

        GenerationWorkspace workspace;
        try {
            workspace = generationWorkspaceService.resolve(appId, targetType);
        } catch (RuntimeException exception) {
            log.warn("Failed to resolve current workspace, appId: {}, taskId: {}, exceptionType: {}",
                    appId, taskId, exception.getClass().getSimpleName());
            return DiffSummary.skipped(appId, taskId, "", "", "current_project_unavailable");
        }
        return summarize(appId, targetType, taskId, rollbackPointArtifact, workspace);
    }

    /** 针对显式捕获的工作区进行汇总，对于异步回调来说是安全的。 */
    public DiffSummary summarize(Long appId,
                                 CodeGenTypeEnum targetType,
                                 String taskId,
                                 GenerationArtifact rollbackPointArtifact,
                                 GenerationWorkspace workspace) {
        // 先处理前置条件和快速返回分支，避免无效输入进入核心流程。
        if (appId == null || appId <= 0 || targetType == null || workspace == null
                || !appId.equals(workspace.appId()) || workspace.codeGenType() != targetType) {
            return DiffSummary.skipped(appId, taskId, "", "", "invalid_generation_context");
        }
        Path currentPath = workspace.canonicalRootPath();
        if (rollbackPointArtifact == null) {
            return DiffSummary.skipped(appId, taskId, "", currentPath.toString(), "rollback_point_missing");
        }
        RollbackPoint rollbackPoint;
        try {
            rollbackPoint = RollbackPoint.fromArtifact(rollbackPointArtifact, appId, taskId);
        } catch (IllegalArgumentException exception) {
            log.warn("Rollback point artifact validation failed while summarizing diff, "
                            + "appId: {}, taskId: {}, exceptionType: {}",
                    appId, taskId, exception.getClass().getSimpleName());
            return DiffSummary.skipped(
                    appId,
                    taskId,
                    "",
                    currentPath.toString(),
                    "rollback_artifact_invalid"
            );
        }
        if (!rollbackPoint.created()) {
            return DiffSummary.skipped(
                    appId,
                    taskId,
                    rollbackPoint.snapshotPath(),
                    currentPath.toString(),
                    "rollback_point_not_created"
            );
        }
        if (!rollbackPoint.trustedForSnapshotConsumption()) {
            return DiffSummary.skipped(
                    appId,
                    taskId,
                    rollbackPoint.snapshotPath(),
                    currentPath.toString(),
                    "rollback_snapshot_identity_unsupported"
            );
        }
        String snapshotPathValue = rollbackPoint.snapshotPath();

        Path basePath;
        try {
            CodeGenTypeEnum sourceType = CodeGenTypeEnum.getEnumByValue(rollbackPoint.sourceType());
            if (sourceType == null) {
                throw new IllegalArgumentException("rollback source type is unsupported");
            }
            SnapshotScope scope = new SnapshotScope(appId, sourceType, rollbackPoint.scope());
            if (!".".equals(scope.relativePath())) {
                throw new IllegalArgumentException("automatic diff only supports the project root scope");
            }
            StoredSnapshot snapshot = snapshotWorkspaceService.requireSnapshot(SnapshotSelector.persisted(
                    rollbackPoint.snapshotName(),
                    scope,
                    rollbackPoint.snapshotId(),
                    SnapshotKind.ROLLBACK_POINT,
                    taskId,
                    rollbackPoint.executionEpoch(),
                    rollbackPoint.manifestSha256()
            ));
            basePath = snapshot.payloadPath();
            snapshotPathValue = snapshot.containerPath().toString();
        } catch (Exception exception) {
            return DiffSummary.skipped(
                    appId,
                    taskId,
                    snapshotPathValue,
                    currentPath.toString(),
                    "rollback_snapshot_validation_failed"
            );
        }
        try {
            return summarizePaths(appId, taskId, basePath, currentPath);
        } catch (GenerationExecutionPolicyException exception) {
            throw exception;
        } catch (Exception exception) {
            log.warn("Failed to summarize workspace diff, appId: {}, taskId: {}, exceptionType: {}",
                    appId, taskId, exception.getClass().getSimpleName());
            return DiffSummary.skipped(
                    appId,
                    taskId,
                    snapshotPathValue,
                    currentPath.toString(),
                    "diff_summary_failed"
            );
        }
    }

    /**
 * 计算{@code marize}{@code Paths}的汇总值。
 *
 * @param appId 应用编号
 * @param taskId 任务编号
 * @param basePath 基础路径
 * @param currentPath 当前路径
 * @return {@code marize}{@code Paths}
 */
    public DiffSummary summarizePaths(Long appId, String taskId, Path basePath, Path currentPath) throws IOException {
        if (!workspaceFileSystemService.isDirectory(basePath)) {
            return DiffSummary.skipped(appId, taskId, pathToString(basePath), pathToString(currentPath), "base_snapshot_missing");
        }
        if (!workspaceFileSystemService.isDirectory(currentPath)) {
            return DiffSummary.skipped(appId, taskId, pathToString(basePath), pathToString(currentPath), "current_project_missing");
        }
        return buildDiffSummary(
                appId,
                taskId,
                basePath.toAbsolutePath().normalize(),
                currentPath.toAbsolutePath().normalize()
        );
    }

    /**
 * 渲染{@code Text}。
 *
 * @param summary 汇总
 * @return 处理后的{@code Text}文本
 */
    public String renderText(DiffSummary summary) {
        if (summary == null) {
            return "差异摘要不可用";
        }
        if (!"created".equals(summary.status())) {
            return "差异摘要已跳过: " + summary.reason();
        }
        StringBuilder builder = new StringBuilder();
        builder.append("生成后差异摘要\n");
        builder.append("新增文件: ").append(summary.addedCount()).append('\n');
        builder.append("修改文件: ").append(summary.modifiedCount()).append('\n');
        builder.append("删除文件: ").append(summary.deletedCount()).append('\n');
        appendSection(builder, "新增", summary.addedFiles());
        appendSection(builder, "删除", summary.deletedFiles());
        appendSection(builder, "修改", summary.modifiedDetails().isEmpty() ? summary.modifiedFiles() : summary.modifiedDetails());
        return builder.toString().trim();
    }

    /** 构建并返回{@code Diff}汇总。 */
    private DiffSummary buildDiffSummary(Long appId, String taskId, Path basePath, Path currentPath) throws IOException {
        Runnable continuationCheck = () -> executionContextService.assertCanContinue(taskId);
        WorkspaceScan baseScan = workspaceFileSystemService.scanProject(basePath, continuationCheck);
        WorkspaceScan currentScan = workspaceFileSystemService.scanProject(currentPath, continuationCheck);
        Map<String, WorkspaceFileMetadata> baseFiles = indexByRelativePath(baseScan);
        Map<String, WorkspaceFileMetadata> currentFiles = indexByRelativePath(currentScan);
        Set<String> allPaths = new TreeSet<>();
        allPaths.addAll(baseFiles.keySet());
        allPaths.addAll(currentFiles.keySet());
        List<String> added = new ArrayList<>();
        List<String> deleted = new ArrayList<>();
        List<String> modified = new ArrayList<>();
        List<String> modifiedDetails = new ArrayList<>();
        // 按既定顺序逐项处理，并在达到资源或状态边界时提前结束。
        for (String relativePath : allPaths) {
            continuationCheck.run();
            WorkspaceFileMetadata baseFile = baseFiles.get(relativePath);
            WorkspaceFileMetadata currentFile = currentFiles.get(relativePath);
            if (baseFile == null) {
                added.add(relativePath);
                continue;
            }
            if (currentFile == null) {
                deleted.add(relativePath);
                continue;
            }
            if (!workspaceFileSystemService.contentEquals(
                    baseScan,
                    baseFile,
                    currentScan,
                    currentFile,
                    continuationCheck
            )) {
                modified.add(relativePath);
                if (modifiedDetails.size() < MAX_FILES) {
                    modifiedDetails.add(buildModifiedDetail(relativePath, baseScan, baseFile, currentScan, currentFile));
                }
            }
        }
        return DiffSummary.created(
                appId,
                taskId,
                basePath.toString(),
                currentPath.toString(),
                added,
                modified,
                deleted,
                modifiedDetails
        );
    }

    /** 构建并返回{@code Modified}{@code Detail}。 */
    private String buildModifiedDetail(String relativePath,
                                       WorkspaceScan baseScan,
                                       WorkspaceFileMetadata baseFile,
                                       WorkspaceScan currentScan,
                                       WorkspaceFileMetadata currentFile) throws IOException {
        String extension = FileUtil.extName(relativePath).toLowerCase(Locale.ROOT);
        if (!TEXT_EXTENSIONS.contains(extension)) {
            return relativePath + " | 内容已变更";
        }
        List<String> beforeLines;
        List<String> afterLines;
        try {
            beforeLines = workspaceFileSystemService
                    .readUtf8(baseScan, baseFile, MAX_DIFF_TEXT_FILE_BYTES)
                    .lines()
                    .toList();
            afterLines = workspaceFileSystemService
                    .readUtf8(currentScan, currentFile, MAX_DIFF_TEXT_FILE_BYTES)
                    .lines()
                    .toList();
        } catch (WorkspaceFileSystemException exception) {
            return relativePath + " | 内容已变更";
        }
        int prefix = commonPrefix(beforeLines, afterLines);
        int suffix = commonSuffix(beforeLines, afterLines, prefix);
        int removed = beforeLines.size() - prefix - suffix;
        int added = afterLines.size() - prefix - suffix;
        return relativePath
                + " | 约 -" + Math.max(removed, 0)
                + " / +" + Math.max(added, 0)
                + " | 变更前: " + previewLine(beforeLines, prefix)
                + " | 变更后: " + previewLine(afterLines, prefix);
    }

    private int commonPrefix(List<String> left, List<String> right) {
        int max = Math.min(left.size(), right.size());
        int index = 0;
        while (index < max && left.get(index).equals(right.get(index))) {
            index++;
        }
        return index;
    }

    private int commonSuffix(List<String> left, List<String> right, int prefix) {
        int leftIndex = left.size() - 1;
        int rightIndex = right.size() - 1;
        int count = 0;
        while (leftIndex >= prefix && rightIndex >= prefix && left.get(leftIndex).equals(right.get(rightIndex))) {
            count++;
            leftIndex--;
            rightIndex--;
        }
        return count;
    }

    private String previewLine(List<String> lines, int index) {
        if (lines.isEmpty()) {
            return "(空)";
        }
        int safeIndex = Math.min(index, lines.size() - 1);
        return lines.get(safeIndex).trim().substring(0, Math.min(lines.get(safeIndex).trim().length(), 120));
    }

    /** 追加{@code Section}。 */
    private void appendSection(StringBuilder builder, String title, List<String> items) {
        if (items.isEmpty()) {
            return;
        }
        builder.append('\n').append('[').append(title).append("]\n");
        items.stream().limit(MAX_FILES).forEach(item -> builder.append("- ").append(item).append('\n'));
        if (items.size() > MAX_FILES) {
            builder.append("- ... 共 ").append(items.size()).append(" 项\n");
        }
    }

    private Map<String, WorkspaceFileMetadata> indexByRelativePath(WorkspaceScan scan) {
        Map<String, WorkspaceFileMetadata> filesByPath = new LinkedHashMap<>();
        for (WorkspaceFileMetadata file : scan.files()) {
            filesByPath.put(file.relativePath(), file);
        }
        return filesByPath;
    }

    private String pathToString(Path path) {
        return path == null ? "" : path.toAbsolutePath().normalize().toString();
    }

}
