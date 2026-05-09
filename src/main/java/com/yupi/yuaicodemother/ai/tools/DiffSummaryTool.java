package com.yupi.yuaicodemother.ai.tools;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import com.yupi.yuaicodemother.constant.AppConstant;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * 变更摘要工具
 */
@Slf4j
@Component
public class DiffSummaryTool extends BaseTool {

    private static final int MAX_FILES = 40;
    private static final Set<String> TEXT_EXTENSIONS = Set.of(
            "vue", "js", "ts", "jsx", "tsx", "json", "css", "scss", "less", "html", "md", "txt", "yml", "yaml"
    );

    @Tool("比较当前项目与快照之间的差异，或者比较两个快照之间的差异，输出新增、修改、删除文件摘要。")
    public String summarizeDiff(
            @P("操作类型：compareLatestSnapshot、compareCurrentWithSnapshot、compareSnapshots")
            String action,
            @P("基准快照名称。compareCurrentWithSnapshot、compareSnapshots 时必填")
            String baseSnapshotName,
            @P("对比快照名称。仅 compareSnapshots 时使用")
            String compareSnapshotName,
            @P("可选，相对项目子目录；为空则比较整个项目")
            String relativeProjectPath,
            @ToolMemoryId Long appId
    ) {
        String normalizedAction = StrUtil.blankToDefault(action, "compareLatestSnapshot");
        try {
            Path projectPath = ToolPathSupport.resolvePath(relativeProjectPath, appId);
            Path snapshotRoot = Path.of(AppConstant.CODE_SNAPSHOT_ROOT_DIR, String.valueOf(appId));
            return switch (normalizedAction) {
                case "compareLatestSnapshot" -> compareLatestSnapshot(projectPath, snapshotRoot);
                case "compareCurrentWithSnapshot" -> compareCurrentWithSnapshot(projectPath, snapshotRoot, baseSnapshotName);
                case "compareSnapshots" -> compareSnapshots(snapshotRoot, baseSnapshotName, compareSnapshotName);
                default -> "错误：不支持的操作类型 - " + normalizedAction;
            };
        } catch (IllegalArgumentException e) {
            return "错误：" + e.getMessage();
        } catch (Exception e) {
            log.error("生成差异摘要失败，action: {}", action, e);
            return "生成差异摘要失败: " + e.getMessage();
        }
    }

    private String compareLatestSnapshot(Path projectPath, Path snapshotRoot) throws Exception {
        Path latestSnapshot = resolveLatestSnapshot(snapshotRoot);
        if (latestSnapshot == null) {
            return "错误：当前没有可对比的快照";
        }
        return buildDiffReport(latestSnapshot, projectPath, latestSnapshot.getFileName().toString(), "current");
    }

    private String compareCurrentWithSnapshot(Path projectPath, Path snapshotRoot, String baseSnapshotName) throws Exception {
        if (StrUtil.isBlank(baseSnapshotName)) {
            return "错误：compareCurrentWithSnapshot 需要提供 baseSnapshotName";
        }
        Path baseSnapshotPath = resolveSnapshot(snapshotRoot, baseSnapshotName);
        return buildDiffReport(baseSnapshotPath, projectPath, baseSnapshotName, "current");
    }

    private String compareSnapshots(Path snapshotRoot, String baseSnapshotName, String compareSnapshotName) throws Exception {
        if (StrUtil.isBlank(baseSnapshotName) || StrUtil.isBlank(compareSnapshotName)) {
            return "错误：compareSnapshots 需要同时提供 baseSnapshotName 和 compareSnapshotName";
        }
        Path baseSnapshotPath = resolveSnapshot(snapshotRoot, baseSnapshotName);
        Path compareSnapshotPath = resolveSnapshot(snapshotRoot, compareSnapshotName);
        return buildDiffReport(baseSnapshotPath, compareSnapshotPath, baseSnapshotName, compareSnapshotName);
    }

    private String buildDiffReport(Path leftRoot, Path rightRoot, String leftName, String rightName) throws Exception {
        if (!Files.exists(leftRoot) || !Files.isDirectory(leftRoot)) {
            return "错误：基准目录不存在 - " + leftName;
        }
        if (!Files.exists(rightRoot) || !Files.isDirectory(rightRoot)) {
            return "错误：对比目录不存在 - " + rightName;
        }
        List<Path> leftFiles = ProjectWorkspaceSupport.listProjectFiles(leftRoot);
        List<Path> rightFiles = ProjectWorkspaceSupport.listProjectFiles(rightRoot);
        Set<String> allPaths = new TreeSet<>();
        leftFiles.forEach(path -> allPaths.add(path.toString().replace(File.separator, "/")));
        rightFiles.forEach(path -> allPaths.add(path.toString().replace(File.separator, "/")));
        List<String> added = new ArrayList<>();
        List<String> deleted = new ArrayList<>();
        List<String> modified = new ArrayList<>();
        List<String> modifiedDetails = new ArrayList<>();
        for (String relativePath : allPaths) {
            Path leftFile = leftRoot.resolve(relativePath);
            Path rightFile = rightRoot.resolve(relativePath);
            boolean leftExists = Files.exists(leftFile);
            boolean rightExists = Files.exists(rightFile);
            if (!leftExists && rightExists) {
                added.add(relativePath);
                continue;
            }
            if (leftExists && !rightExists) {
                deleted.add(relativePath);
                continue;
            }
            if (!sameFileContent(leftFile, rightFile)) {
                modified.add(relativePath);
                if (modifiedDetails.size() < MAX_FILES) {
                    modifiedDetails.add(buildModifiedDetail(relativePath, leftFile, rightFile));
                }
            }
        }
        return buildSummaryText(leftName, rightName, added, deleted, modified, modifiedDetails);
    }

    private boolean sameFileContent(Path leftFile, Path rightFile) throws Exception {
        return FileUtil.contentEquals(leftFile.toFile(), rightFile.toFile());
    }

    private String buildModifiedDetail(String relativePath, Path leftFile, Path rightFile) {
        String extension = FileUtil.extName(relativePath).toLowerCase();
        if (!TEXT_EXTENSIONS.contains(extension)) {
            return relativePath + " | 内容已变更";
        }
        List<String> beforeLines = FileUtil.readLines(leftFile.toFile(), StandardCharsets.UTF_8);
        List<String> afterLines = FileUtil.readLines(rightFile.toFile(), StandardCharsets.UTF_8);
        int prefix = commonPrefix(beforeLines, afterLines);
        int suffix = commonSuffix(beforeLines, afterLines, prefix);
        int removed = beforeLines.size() - prefix - suffix;
        int added = afterLines.size() - prefix - suffix;
        String beforePreview = previewLine(beforeLines, prefix);
        String afterPreview = previewLine(afterLines, prefix);
        return relativePath
                + " | 约 -" + Math.max(removed, 0)
                + " / +" + Math.max(added, 0)
                + " | 变更前: " + beforePreview
                + " | 变更后: " + afterPreview;
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
        return StrUtil.sub(lines.get(safeIndex).trim(), 0, 120);
    }

    private String buildSummaryText(String leftName, String rightName, List<String> added, List<String> deleted,
                                    List<String> modified, List<String> modifiedDetails) {
        StringBuilder builder = new StringBuilder();
        builder.append("差异对比: ").append(leftName).append(" -> ").append(rightName).append('\n');
        builder.append("新增文件: ").append(added.size()).append('\n');
        builder.append("删除文件: ").append(deleted.size()).append('\n');
        builder.append("修改文件: ").append(modified.size()).append('\n');
        appendSection(builder, "新增", added);
        appendSection(builder, "删除", deleted);
        appendSection(builder, "修改", modifiedDetails.isEmpty() ? modified : modifiedDetails);
        return builder.toString().trim();
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

    private Path resolveLatestSnapshot(Path snapshotRoot) throws Exception {
        if (!Files.exists(snapshotRoot) || !Files.isDirectory(snapshotRoot)) {
            return null;
        }
        try (var stream = Files.list(snapshotRoot)) {
            return stream
                    .filter(Files::isDirectory)
                    .max(Comparator.comparingLong(path -> path.toFile().lastModified()))
                    .orElse(null);
        }
    }

    private Path resolveSnapshot(Path snapshotRoot, String snapshotName) {
        Path snapshotPath = snapshotRoot.resolve(snapshotName).normalize();
        ToolPathSupport.ensureWithinProject(snapshotRoot, snapshotPath);
        return snapshotPath;
    }

    @Override
    public String getToolName() {
        return "summarizeDiff";
    }

    @Override
    public String getDisplayName() {
        return "差异摘要";
    }

    @Override
    public String generateToolExecutedResult(JSONObject arguments) {
        return String.format("[工具调用] %s %s", getDisplayName(), arguments.getStr("action"));
    }

    @Override
    public String generateToolExecutedResult(JSONObject arguments, String toolResult) {
        return generateToolExecutedResult(arguments) + "\n" + summarizeResult(toolResult, 320);
    }

    private String summarizeResult(String toolResult, int maxChars) {
        if (StrUtil.isBlank(toolResult)) {
            return "";
        }
        String normalized = toolResult.replace("\r", " ").replace("\n", " ").trim();
        return StrUtil.sub(normalized, 0, Math.min(normalized.length(), maxChars));
    }
}
