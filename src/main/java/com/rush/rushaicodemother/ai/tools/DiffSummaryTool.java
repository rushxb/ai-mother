package com.rush.rushaicodemother.ai.tools;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import com.rush.rushaicodemother.infrastructure.diagnostic.LogExceptionSanitizer;
import com.rush.rushaicodemother.infrastructure.filesystem.WorkspaceFileSystemService;
import com.rush.rushaicodemother.orchestration.artifact.DiffSummary;
import com.rush.rushaicodemother.orchestration.snapshot.GenerationDiffSummaryService;
import com.rush.rushaicodemother.orchestration.snapshot.GenerationSnapshotWorkspaceService;
import com.rush.rushaicodemother.orchestration.snapshot.SnapshotNamePolicy;
import com.rush.rushaicodemother.orchestration.snapshot.SnapshotScope;
import com.rush.rushaicodemother.orchestration.snapshot.SnapshotSelector;
import com.rush.rushaicodemother.orchestration.snapshot.StoredSnapshot;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionPolicyException;
import com.rush.rushaicodemother.orchestration.tool.GenerationToolExecutionContext;
import com.rush.rushaicodemother.orchestration.tool.GenerationToolExecutionContextService;
import com.rush.rushaicodemother.orchestration.tool.ToolPublicFailureException;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.Objects;

/**
 * 变更摘要工具
 */
@Slf4j
@Component
public class DiffSummaryTool extends BaseTool {

    private final GenerationDiffSummaryService generationDiffSummaryService;
    private final ToolWorkspaceFileService workspaceFileService;
    private final WorkspaceFileSystemService workspaceFileSystemService;
    private final GenerationSnapshotWorkspaceService snapshotWorkspaceService;
    private final SnapshotNamePolicy snapshotNamePolicy;
    private final GenerationToolExecutionContextService toolExecutionContextService;

    public DiffSummaryTool(
            GenerationDiffSummaryService generationDiffSummaryService,
            ToolWorkspaceFileService workspaceFileService,
            WorkspaceFileSystemService workspaceFileSystemService,
            GenerationSnapshotWorkspaceService snapshotWorkspaceService,
            SnapshotNamePolicy snapshotNamePolicy,
            GenerationToolExecutionContextService toolExecutionContextService
    ) {
        this.generationDiffSummaryService = Objects.requireNonNull(
                generationDiffSummaryService, "generationDiffSummaryService must not be null");
        this.workspaceFileService = Objects.requireNonNull(
                workspaceFileService, "workspaceFileService must not be null");
        this.workspaceFileSystemService = Objects.requireNonNull(
                workspaceFileSystemService, "workspaceFileSystemService must not be null");
        this.snapshotWorkspaceService = Objects.requireNonNull(
                snapshotWorkspaceService, "snapshotWorkspaceService must not be null");
        this.snapshotNamePolicy = Objects.requireNonNull(
                snapshotNamePolicy, "snapshotNamePolicy must not be null");
        this.toolExecutionContextService = Objects.requireNonNull(
                toolExecutionContextService, "toolExecutionContextService must not be null");
    }

    /**
 * 计算{@code marize}{@code Diff}的汇总值。
 *
 * @param action 动作
 * @param baseSnapshotName 基础快照名称
 * @param compareSnapshotName {@code compareSnapshotName} 对应的调用参数
 * @param relativeProjectPath 项目相对路径
 * @param appId 应用编号
 * @return 处理后的{@code marize}{@code Diff}文本
 */
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
        // 将可能失败的操作收敛在统一异常边界内，便于清理资源和转换错误。
        try {
            requireAppId(appId);
            SnapshotScope scope = resolveSnapshotScope(appId, relativeProjectPath);
            return switch (normalizedAction) {
                case "compareLatestSnapshot" -> compareLatestSnapshot(
                        appId,
                        resolveProjectPath(appId, relativeProjectPath),
                        scope
                );
                case "compareCurrentWithSnapshot" -> {
                    String normalizedBaseName = validateRequiredSnapshotName(
                            baseSnapshotName,
                            "compareCurrentWithSnapshot 需要提供 baseSnapshotName"
                    );
                    yield compareCurrentWithSnapshot(
                            appId,
                            resolveProjectPath(appId, relativeProjectPath),
                            scope,
                            normalizedBaseName
                    );
                }
                case "compareSnapshots" -> compareSnapshots(
                        appId,
                        scope,
                        validateRequiredSnapshotName(baseSnapshotName, "compareSnapshots 需要同时提供 baseSnapshotName 和 compareSnapshotName"),
                        validateRequiredSnapshotName(compareSnapshotName, "compareSnapshots 需要同时提供 baseSnapshotName 和 compareSnapshotName")
                );
                default -> throw toolFailure("错误：不支持的操作类型 - " + normalizedAction);
            };
        } catch (ToolPublicFailureException publicFailure) {
            throw publicFailure;
        } catch (GenerationExecutionPolicyException executionPolicyFailure) {
            throw executionPolicyFailure;
        } catch (ToolInputException e) {
            throw toolInputFailure("错误：", e);
        } catch (SnapshotNamePolicy.ValidationException e) {
            throw toolInputFailure("错误：", new ToolInputException(e.getMessage(), e));
        } catch (Exception e) {
            log.error("生成差异摘要失败，action: {}, exceptionType: {}",
                    action, e.getClass().getSimpleName());
            throw toolFailure("生成差异摘要失败，请稍后重试");
        }
    }

    private String compareLatestSnapshot(Long appId,
                                         Path projectPath,
                                         SnapshotScope scope) throws Exception {
        StoredSnapshot latestSnapshot = resolveLatestSnapshot(appId, scope);
        if (latestSnapshot == null) {
            throw toolFailure("错误：当前没有可对比的快照");
        }
        return buildDiffReport(
                latestSnapshot.payloadPath(),
                projectPath,
                latestSnapshot.snapshotName(),
                "current"
        );
    }

    private String compareCurrentWithSnapshot(Long appId,
                                              Path projectPath,
                                              SnapshotScope scope,
                                              String baseSnapshotName) throws Exception {
        StoredSnapshot baseSnapshot = resolveSnapshot(baseSnapshotName, scope);
        return buildDiffReport(baseSnapshot.payloadPath(), projectPath, baseSnapshotName, "current");
    }

    private String compareSnapshots(Long appId,
                                    SnapshotScope scope,
                                    String baseSnapshotName,
                                    String compareSnapshotName) throws Exception {
        StoredSnapshot baseSnapshot = resolveSnapshot(baseSnapshotName, scope);
        StoredSnapshot compareSnapshot = resolveSnapshot(compareSnapshotName, scope);
        return buildDiffReport(
                baseSnapshot.payloadPath(),
                compareSnapshot.payloadPath(),
                baseSnapshotName,
                compareSnapshotName
        );
    }

    /** 构建并返回{@code Diff}报告。 */
    private String buildDiffReport(Path leftRoot, Path rightRoot, String leftName, String rightName) throws Exception {
        if (!workspaceFileSystemService.isDirectory(leftRoot)) {
            throw toolFailure("错误：基准目录不存在 - " + leftName);
        }
        if (!workspaceFileSystemService.isDirectory(rightRoot)) {
            throw toolFailure("错误：对比目录不存在 - " + rightName);
        }
        DiffSummary summary = generationDiffSummaryService.summarizePaths(null, "", leftRoot, rightRoot);
        if (!summary.created()) {
            log.warn("差异摘要未生成，reason: {}", LogExceptionSanitizer.sanitizeValue(summary.reason(), 200));
            throw toolFailure("错误：差异摘要生成失败，请稍后重试");
        }
        String rendered = generationDiffSummaryService.renderText(summary);
        return rendered.replaceFirst("生成后差异摘要", "差异对比: " + leftName + " -> " + rightName);
    }

    private StoredSnapshot resolveLatestSnapshot(Long appId, SnapshotScope scope) throws Exception {
        return snapshotWorkspaceService.listSnapshots(appId).stream()
                .filter(snapshot -> snapshot.scope().equals(scope))
                .findFirst()
                .orElse(null);
    }

    private StoredSnapshot resolveSnapshot(String snapshotName, SnapshotScope scope) throws Exception {
        return snapshotWorkspaceService.requireSnapshot(SnapshotSelector.forWorkspace(snapshotName, scope));
    }

    private SnapshotScope resolveSnapshotScope(Long appId, String relativeProjectPath) {
        GenerationToolExecutionContext context = toolExecutionContextService.getContext(appId)
                .orElseThrow(() -> new ToolInputException("差异摘要缺少生成任务上下文"));
        if (context.codeGenType() == null) {
            throw new ToolInputException("差异摘要缺少工程类型");
        }
        return new SnapshotScope(
                appId,
                context.codeGenType(),
                SnapshotScope.normalizeRelativePath(relativeProjectPath)
        );
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
    public ToolRiskLevel getRiskLevel() {
        return ToolRiskLevel.READ_ONLY;
    }

    @Override
    public String getToolName() {
        return "summarizeDiff";
    }

    @Override
    public String getDisplayName() {
        return "差异摘要";
    }

    /**
 * 将工具执行结果整理为模型可消费的文本。
 *
 * @param arguments 参数
 * @return 处理后的方法执行结果文本
 */
    @Override
    public String generateToolExecutedResult(JSONObject arguments) {
        return String.format("[工具调用] %s %s", getDisplayName(), arguments.getStr("action"));
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
