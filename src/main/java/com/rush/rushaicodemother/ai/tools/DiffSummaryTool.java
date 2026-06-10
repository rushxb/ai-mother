package com.rush.rushaicodemother.ai.tools;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import com.rush.rushaicodemother.constant.AppConstant;
import com.rush.rushaicodemother.orchestration.artifact.DiffSummary;
import com.rush.rushaicodemother.orchestration.snapshot.GenerationDiffSummaryService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/**
 * 变更摘要工具
 */
@Slf4j
@Component
public class DiffSummaryTool extends BaseTool {

    private final GenerationDiffSummaryService generationDiffSummaryService;

    @Autowired
    public DiffSummaryTool(GenerationDiffSummaryService generationDiffSummaryService) {
        this.generationDiffSummaryService = generationDiffSummaryService;
    }

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
        DiffSummary summary = generationDiffSummaryService.summarizePaths(null, "", leftRoot, rightRoot);
        if (!"created".equals(summary.status())) {
            return "错误：差异摘要生成失败 - " + summary.reason();
        }
        String rendered = generationDiffSummaryService.renderText(summary);
        return rendered.replaceFirst("生成后差异摘要", "差异对比: " + leftName + " -> " + rightName);
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
