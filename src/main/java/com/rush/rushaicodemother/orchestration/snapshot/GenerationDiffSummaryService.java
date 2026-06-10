package com.rush.rushaicodemother.orchestration.snapshot;

import cn.hutool.core.io.FileUtil;
import com.rush.rushaicodemother.constant.AppConstant;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.artifact.DiffSummary;
import com.rush.rushaicodemother.orchestration.artifact.GenerationArtifact;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * 根据生成前回滚点生成非破坏性的生成后差异摘要。
 */
@Slf4j
@Component
public class GenerationDiffSummaryService {

    private static final int MAX_FILES = 40;
    private static final Set<String> DEFAULT_IGNORED_NAMES = Set.of(
            ".git", ".idea", ".vscode", "node_modules", "dist", "build", "target", "coverage"
    );
    private static final Set<String> TEXT_EXTENSIONS = Set.of(
            "vue", "js", "ts", "jsx", "tsx", "json", "css", "scss", "less", "html", "md", "txt", "yml", "yaml"
    );

    private final Path codeOutputRoot;

    public GenerationDiffSummaryService() {
        this(Path.of(AppConstant.CODE_OUTPUT_ROOT_DIR));
    }

    public GenerationDiffSummaryService(Path codeOutputRoot) {
        this.codeOutputRoot = codeOutputRoot.toAbsolutePath().normalize();
    }

    public DiffSummary summarize(Long appId,
                                  CodeGenTypeEnum targetType,
                                  String taskId,
                                  GenerationArtifact rollbackPointArtifact) {
        Path currentPath = resolveProjectPath(appId, targetType);
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
        Path basePath = Path.of(String.valueOf(rollbackPayload.get("snapshotPath"))).toAbsolutePath().normalize();
        if (!Files.isDirectory(basePath)) {
            return DiffSummary.skipped(appId, taskId, basePath.toString(), currentPath.toString(), "base_snapshot_missing");
        }
        if (!Files.isDirectory(currentPath)) {
            return DiffSummary.skipped(appId, taskId, basePath.toString(), currentPath.toString(), "current_project_missing");
        }
        try {
            return summarizePaths(appId, taskId, basePath, currentPath);
        } catch (Exception e) {
            log.warn("生成后差异摘要失败，appId: {}, taskId: {}", appId, taskId, e);
            return DiffSummary.skipped(appId, taskId, basePath.toString(), currentPath.toString(), "diff_summary_failed:" + e.getMessage());
        }
    }

    public DiffSummary summarizePaths(Long appId, String taskId, Path basePath, Path currentPath) throws IOException {
        if (!Files.isDirectory(basePath)) {
            return DiffSummary.skipped(appId, taskId, pathToString(basePath), pathToString(currentPath), "base_snapshot_missing");
        }
        if (!Files.isDirectory(currentPath)) {
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
        List<Path> baseFiles = listProjectFiles(basePath);
        List<Path> currentFiles = listProjectFiles(currentPath);
        Set<String> allPaths = new TreeSet<>();
        baseFiles.forEach(path -> allPaths.add(path.toString().replace("\\", "/")));
        currentFiles.forEach(path -> allPaths.add(path.toString().replace("\\", "/")));
        List<String> added = new ArrayList<>();
        List<String> deleted = new ArrayList<>();
        List<String> modified = new ArrayList<>();
        List<String> modifiedDetails = new ArrayList<>();
        for (String relativePath : allPaths) {
            Path baseFile = basePath.resolve(relativePath);
            Path currentFile = currentPath.resolve(relativePath);
            boolean baseExists = Files.exists(baseFile);
            boolean currentExists = Files.exists(currentFile);
            if (!baseExists && currentExists) {
                added.add(relativePath);
                continue;
            }
            if (baseExists && !currentExists) {
                deleted.add(relativePath);
                continue;
            }
            if (!FileUtil.contentEquals(baseFile.toFile(), currentFile.toFile())) {
                modified.add(relativePath);
                if (modifiedDetails.size() < MAX_FILES) {
                    modifiedDetails.add(buildModifiedDetail(relativePath, baseFile, currentFile));
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

    private String buildModifiedDetail(String relativePath, Path baseFile, Path currentFile) {
        String extension = FileUtil.extName(relativePath).toLowerCase();
        if (!TEXT_EXTENSIONS.contains(extension)) {
            return relativePath + " | 内容已变更";
        }
        List<String> beforeLines = FileUtil.readLines(baseFile.toFile(), StandardCharsets.UTF_8);
        List<String> afterLines = FileUtil.readLines(currentFile.toFile(), StandardCharsets.UTF_8);
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

    private List<Path> listProjectFiles(Path root) throws IOException {
        try (Stream<Path> stream = Files.walk(root)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> shouldInclude(root, path))
                    .map(root::relativize)
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        }
    }

    private boolean shouldInclude(Path root, Path path) {
        Path relative = root.equals(path) ? Path.of("") : root.relativize(path);
        for (Path part : relative.normalize()) {
            if (DEFAULT_IGNORED_NAMES.contains(part.toString())) {
                return false;
            }
        }
        return true;
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
}
