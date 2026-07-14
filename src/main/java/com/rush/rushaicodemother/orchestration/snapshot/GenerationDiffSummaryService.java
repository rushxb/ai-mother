package com.rush.rushaicodemother.orchestration.snapshot;

import cn.hutool.core.io.FileUtil;
import com.rush.rushaicodemother.constant.AppConstant;
import com.rush.rushaicodemother.infrastructure.filesystem.WorkspaceFileSystemException;
import com.rush.rushaicodemother.infrastructure.filesystem.WorkspaceFileSystemService;
import com.rush.rushaicodemother.infrastructure.filesystem.WorkspaceFileSystemService.WorkspaceFileMetadata;
import com.rush.rushaicodemother.infrastructure.filesystem.WorkspaceFileSystemService.WorkspaceScan;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.artifact.DiffSummary;
import com.rush.rushaicodemother.orchestration.artifact.GenerationArtifact;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
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

    private final Path codeOutputRoot;
    private final Path codeSnapshotRoot;
    private final WorkspaceFileSystemService workspaceFileSystemService;

    @Autowired
    public GenerationDiffSummaryService(WorkspaceFileSystemService workspaceFileSystemService) {
        this(
                Path.of(AppConstant.CODE_OUTPUT_ROOT_DIR),
                Path.of(AppConstant.CODE_SNAPSHOT_ROOT_DIR),
                workspaceFileSystemService
        );
    }

    public GenerationDiffSummaryService(Path codeOutputRoot,
                                        Path codeSnapshotRoot,
                                        WorkspaceFileSystemService workspaceFileSystemService) {
        this.codeOutputRoot = codeOutputRoot.toAbsolutePath().normalize();
        this.codeSnapshotRoot = codeSnapshotRoot.toAbsolutePath().normalize();
        this.workspaceFileSystemService = workspaceFileSystemService;
    }

    public DiffSummary summarize(Long appId,
                                  CodeGenTypeEnum targetType,
                                  String taskId,
                                  GenerationArtifact rollbackPointArtifact) {
        Path currentPath = resolveProjectPath(appId, targetType);
        if (appId == null || appId <= 0 || targetType == null) {
            return DiffSummary.skipped(appId, taskId, "", currentPath.toString(), "invalid_generation_context");
        }
        if (rollbackPointArtifact == null || rollbackPointArtifact.payload() == null) {
            return DiffSummary.skipped(appId, taskId, "", currentPath.toString(), "rollback_point_missing");
        }
        Map<String, Object> rollbackPayload = rollbackPointArtifact.payload();
        if (!"created".equals(String.valueOf(rollbackPayload.get("status")))) {
            return DiffSummary.skipped(
                    appId,
                    taskId,
                    String.valueOf(rollbackPayload.getOrDefault("snapshotPath", "")),
                    currentPath.toString(),
                    "rollback_point_not_created"
            );
        }
        String snapshotPathValue = String.valueOf(rollbackPayload.getOrDefault("snapshotPath", ""));
        if (!matchesArtifactContext(appId, taskId, rollbackPayload)) {
            return DiffSummary.skipped(
                    appId,
                    taskId,
                    snapshotPathValue,
                    currentPath.toString(),
                    "rollback_artifact_context_mismatch"
            );
        }
        Path basePath;
        try {
            basePath = Path.of(snapshotPathValue).toAbsolutePath().normalize();
            if (!isAllowedSnapshotPath(appId, basePath, rollbackPayload)) {
                return DiffSummary.skipped(
                        appId,
                        taskId,
                        snapshotPathValue,
                        currentPath.toString(),
                        "rollback_path_out_of_root"
                );
            }
            return summarizePaths(appId, taskId, basePath, currentPath);
        } catch (Exception e) {
            log.warn("生成后差异摘要失败，appId: {}, taskId: {}, exceptionType: {}",
                    appId, taskId, e.getClass().getSimpleName());
            return DiffSummary.skipped(
                    appId,
                    taskId,
                    snapshotPathValue,
                    currentPath.toString(),
                    "diff_summary_failed"
            );
        }
    }

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

    private DiffSummary buildDiffSummary(Long appId, String taskId, Path basePath, Path currentPath) throws IOException {
        WorkspaceScan baseScan = workspaceFileSystemService.scanProject(basePath);
        WorkspaceScan currentScan = workspaceFileSystemService.scanProject(currentPath);
        Map<String, WorkspaceFileMetadata> baseFiles = indexByRelativePath(baseScan);
        Map<String, WorkspaceFileMetadata> currentFiles = indexByRelativePath(currentScan);
        Set<String> allPaths = new TreeSet<>();
        allPaths.addAll(baseFiles.keySet());
        allPaths.addAll(currentFiles.keySet());
        List<String> added = new ArrayList<>();
        List<String> deleted = new ArrayList<>();
        List<String> modified = new ArrayList<>();
        List<String> modifiedDetails = new ArrayList<>();
        for (String relativePath : allPaths) {
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
            if (!workspaceFileSystemService.contentEquals(baseScan, baseFile, currentScan, currentFile)) {
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

    private Path resolveProjectPath(Long appId, CodeGenTypeEnum targetType) {
        if (appId == null || appId <= 0 || targetType == null) {
            return codeOutputRoot;
        }
        return codeOutputRoot.resolve(targetType.getValue() + "_" + appId)
                .toAbsolutePath()
                .normalize();
    }

    private String pathToString(Path path) {
        return path == null ? "" : path.toAbsolutePath().normalize().toString();
    }

    private boolean matchesArtifactContext(Long appId,
                                           String taskId,
                                           Map<String, Object> rollbackPayload) {
        return taskId != null
                && !taskId.isBlank()
                && String.valueOf(appId).equals(String.valueOf(rollbackPayload.get("appId")))
                && String.valueOf(taskId).equals(String.valueOf(rollbackPayload.get("taskId")));
    }

    private boolean isAllowedSnapshotPath(Long appId,
                                          Path snapshotPath,
                                          Map<String, Object> rollbackPayload) {
        Path applicationSnapshotRoot = codeSnapshotRoot.resolve(String.valueOf(appId)).normalize();
        if (!snapshotPath.startsWith(applicationSnapshotRoot)
                || snapshotPath.equals(applicationSnapshotRoot)
                || !applicationSnapshotRoot.equals(snapshotPath.getParent())) {
            return false;
        }
        String snapshotName = String.valueOf(rollbackPayload.getOrDefault("snapshotName", ""));
        return snapshotName.isBlank() || snapshotName.equals(snapshotPath.getFileName().toString());
    }
}
