package com.rush.rushaicodemother.service.impl;

import cn.hutool.core.util.StrUtil;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.entity.GenerationBuildLog;
import com.rush.rushaicodemother.model.entity.GenerationTask;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.service.GenerationContextCompressionService;
import com.rush.rushaicodemother.service.GenerationMemoryContextService;
import com.rush.rushaicodemother.service.GenerationTraceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class GenerationMemoryContextServiceImpl implements GenerationMemoryContextService {

    private static final int MAX_CONTEXT_LENGTH = 3200;
    private static final int MAX_FIELD_LENGTH = 600;
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final GenerationTraceService generationTraceService;

    private final GenerationContextCompressionService generationContextCompressionService;

    @Override
    public String buildGenerationMemoryContext(App app, String userMessage, CodeGenTypeEnum targetType) {
        if (app == null || app.getId() == null) {
            return "";
        }
        List<GenerationTask> recentTasks = generationTraceService.listRecentTasksByAppId(app.getId(), 5);
        List<GenerationBuildLog> recentBuildLogs = generationTraceService.listRecentBuildLogsByAppId(app.getId(), 3);
        if (recentTasks.isEmpty() && recentBuildLogs.isEmpty()) {
            return "";
        }
        List<String> lines = new ArrayList<>();
        lines.add("【AI对话记忆】以下是压缩后的历史上下文，只用于延续目标、避免重复失败和减少无关改动，不要逐字复述。");
        lines.add("当前应用：appId=" + app.getId()
                + ", appName=" + StrUtil.blankToDefault(app.getAppName(), "未命名")
                + ", targetType=" + (targetType == null ? StrUtil.blankToDefault(app.getCodeGenType(), "unknown") : targetType.getValue()));
        appendRecentTasks(lines, recentTasks, userMessage);
        if (recentTasks.stream().noneMatch(task -> StrUtil.isNotBlank(task.getMemorySummary()))) {
            appendRecentBuildLogs(lines, recentBuildLogs);
        }
        lines.add("记忆使用规则：优先满足本轮用户需求；历史记录只作为边界和失败经验；不要因为旧需求扩大本轮改动范围。");
        return generationContextCompressionService.compressMemoryContext(limit(String.join("\n", lines), MAX_CONTEXT_LENGTH));
    }

    @Override
    public String buildAutoRepairMemoryContext(Long appId, String taskId, String errorMessage, int repairRound) {
        if (appId == null) {
            return "";
        }
        List<GenerationBuildLog> taskBuildLogs = StrUtil.isBlank(taskId)
                ? List.of()
                : generationTraceService.listBuildLogsByTaskId(taskId, 3);
        List<GenerationBuildLog> recentBuildLogs = generationTraceService.listRecentBuildLogsByAppId(appId, 3);
        if (taskBuildLogs.isEmpty() && recentBuildLogs.isEmpty()) {
            return "";
        }
        List<String> lines = new ArrayList<>();
        lines.add("【自修记忆】以下是压缩后的构建和工具轨迹，只用于定位本轮失败根因。");
        lines.add("appId=" + appId + ", taskId=" + StrUtil.blankToDefault(taskId, "unknown") + ", repairRound=" + repairRound);
        if (StrUtil.isNotBlank(errorMessage)) {
            lines.add("当前错误摘要：" + compact(errorMessage));
        }
        appendBuildLogs(lines, "本任务构建记录", taskBuildLogs);
        appendBuildLogs(lines, "最近构建记录", recentBuildLogs);
        lines.add("自修规则：只读相关文件、只改直接相关代码；不要重建项目。");
        return generationContextCompressionService.compressMemoryContext(limit(String.join("\n", lines), MAX_CONTEXT_LENGTH));
    }

    private void appendRecentTasks(List<String> lines, List<GenerationTask> recentTasks, String userMessage) {
        if (recentTasks.isEmpty()) {
            return;
        }
        lines.add("最近生成任务：");
        for (GenerationTask task : recentTasks) {
            String relation = isRelated(userMessage, task.getUserPrompt()) ? "相关" : "参考";
            if (StrUtil.isNotBlank(task.getMemorySummary())) {
                lines.add("- [" + relation + "] " + formatTime(task.getCreateTime())
                        + " status=" + StrUtil.blankToDefault(task.getStatus(), "unknown")
                        + ", summary=" + compact(task.getMemorySummary()));
                continue;
            }
            lines.add("- [" + relation + "] " + formatTime(task.getCreateTime())
                    + " status=" + StrUtil.blankToDefault(task.getStatus(), "unknown")
                    + ", stage=" + StrUtil.blankToDefault(task.getStage(), "unknown")
                    + ", prompt=" + compact(task.getUserPrompt())
                    + formatError(task.getErrorMessage()));
        }
    }

    private void appendRecentBuildLogs(List<String> lines, List<GenerationBuildLog> buildLogs) {
        appendBuildLogs(lines, "最近构建诊断", buildLogs);
    }

    private void appendBuildLogs(List<String> lines, String title, List<GenerationBuildLog> buildLogs) {
        if (buildLogs.isEmpty()) {
            return;
        }
        lines.add(title + "：");
        for (GenerationBuildLog log : buildLogs) {
            lines.add("- " + formatTime(log.getCreateTime())
                    + " success=" + log.getSuccess()
                    + ", stage=" + StrUtil.blankToDefault(log.getStage(), "unknown")
                    + ", summary=" + compact(log.getSummary())
                    + formatReport(log.getReport()));
        }
    }

    private boolean isRelated(String userMessage, String historicalPrompt) {
        if (StrUtil.isBlank(userMessage) || StrUtil.isBlank(historicalPrompt)) {
            return false;
        }
        String normalizedUserMessage = userMessage.toLowerCase(Locale.ROOT);
        String normalizedHistoricalPrompt = historicalPrompt.toLowerCase(Locale.ROOT);
        for (String token : normalizedUserMessage.split("\\s+|，|。|、|,|\\.")) {
            if (token.length() >= 3 && normalizedHistoricalPrompt.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private String formatTime(java.time.LocalDateTime time) {
        return time == null ? "unknown_time" : TIME_FORMATTER.format(time);
    }

    private String formatError(String errorMessage) {
        return StrUtil.isBlank(errorMessage) ? "" : ", note=" + compact(errorMessage);
    }

    private String formatReport(String report) {
        return StrUtil.isBlank(report) ? "" : ", report=" + compact(report);
    }

    private String compact(String value) {
        if (StrUtil.isBlank(value)) {
            return "";
        }
        return limit(value.replaceAll("\\s+", " ").trim(), MAX_FIELD_LENGTH);
    }

    private String limit(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "...";
    }
}
