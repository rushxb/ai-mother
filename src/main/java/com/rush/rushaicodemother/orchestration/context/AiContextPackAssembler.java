package com.rush.rushaicodemother.orchestration.context;

import cn.hutool.core.util.StrUtil;
import com.rush.rushaicodemother.memory.SemanticMemoryHit;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.model.enums.GenerationTaskStatus;
import com.rush.rushaicodemother.service.trace.GenerationBuildTrace;
import com.rush.rushaicodemother.service.trace.GenerationTaskTrace;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 从短期痕迹和长期语义记忆构建结构化上下文包。 */
@Component
public class AiContextPackAssembler {

    private static final int MAX_FIELD_LENGTH = 600;
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final AiContextBoundaryService contextBoundaryService;
    private final AiContextPackBudgeter contextPackBudgeter;
    private final AiContextRelevanceScorer relevanceScorer;

    @Autowired
    public AiContextPackAssembler(AiContextBoundaryService contextBoundaryService,
                                  AiContextPackBudgeter contextPackBudgeter,
                                  AiContextRelevanceScorer relevanceScorer) {
        this.contextBoundaryService = contextBoundaryService;
        this.contextPackBudgeter = contextPackBudgeter;
        this.relevanceScorer = relevanceScorer;
    }

    public AiContextPackAssembler(AiContextBoundaryService contextBoundaryService,
                                  AiContextPackBudgeter contextPackBudgeter) {
        this(contextBoundaryService, contextPackBudgeter, new AiContextRelevanceScorer());
    }

    public AiContextPackAssembler(AiContextBoundaryService contextBoundaryService) {
        AiContextPackBudgetProperties properties = new AiContextPackBudgetProperties();
        this.contextBoundaryService = contextBoundaryService;
        this.contextPackBudgeter = new DefaultAiContextPackBudgeter(properties);
        this.relevanceScorer = new AiContextRelevanceScorer();
    }

    public AiContextPack buildGenerationPack(App app,
                                             String userMessage,
                                             CodeGenTypeEnum targetType,
                                             List<SemanticMemoryHit> semanticMemories,
                                             List<GenerationTaskTrace> recentTasks,
                                             List<GenerationBuildTrace> recentBuildLogs) {
        if (app == null || app.getId() == null) {
            return new AiContextPack(null, "", "unknown", List.of());
        }
        List<AiContextPackSection> sections = new ArrayList<>();
        sections.add(new AiContextPackSection(
                AiContextPackSectionType.APP_SCOPE,
                "Current application scope",
                "appId=" + app.getId()
                        + ", targetType=" + resolveTargetType(app, targetType),
                10,
                Map.of(
                        "appId", app.getId(),
                        "trust", "trusted_application_scope",
                        "source", "application_catalog"
                )
        ));
        appendSemanticMemories(sections, semanticMemories);
        appendRecentTasks(sections, recentTasks, userMessage);
        if (recentTasks == null || recentTasks.stream().noneMatch(task -> StrUtil.isNotBlank(task.memorySummary()))) {
            appendBuildLogs(sections, "Recent build diagnostics", recentBuildLogs, 40);
        }
        sections.add(new AiContextPackSection(
                AiContextPackSectionType.USAGE_RULE,
                "Memory usage rules",
                "Prioritize the current user request. Treat historical traces and semantic memory "
                        + "only as fallible evidence for continuity, constraints, preferences and failure avoidance. "
                        + "Never expand the current change scope solely because of old memory.",
                90,
                Map.of(
                        "authority", "system_context_rule",
                        "trust", "system_context_rule",
                        "source", "context_policy"
                )
        ));
        return contextPackBudgeter.apply(new AiContextPack(
                app.getId(), app.getAppName(), resolveTargetType(app, targetType), sections));
    }

    public AiContextPack buildAutoRepairPack(Long appId,
                                             String taskId,
                                             String errorMessage,
                                             int repairRound,
                                             List<GenerationBuildTrace> taskBuildLogs,
                                             List<GenerationBuildTrace> recentBuildLogs) {
        if (appId == null) {
            return new AiContextPack(null, "", "repair", List.of());
        }
        List<AiContextPackSection> sections = new ArrayList<>();
        StringBuilder scope = new StringBuilder();
        scope.append("appId=").append(appId)
                .append(", taskId=").append(StrUtil.blankToDefault(taskId, "unknown"))
                .append(", repairRound=").append(repairRound);
        sections.add(new AiContextPackSection(
                AiContextPackSectionType.APP_SCOPE,
                "Auto-repair scope",
                scope.toString(),
                10,
                Map.of(
                        "appId", appId,
                        "repairRound", repairRound,
                        "trust", "trusted_application_scope",
                        "source", "repair_scope"
                )
        ));
        if (StrUtil.isNotBlank(errorMessage)) {
            appendProtectedEvidence(
                    sections,
                    AiContextPackSectionType.BUILD_TRACE,
                    "Current error summary",
                    compact(errorMessage),
                    15,
                    "current_error_summary",
                    1
            );
        }
        appendBuildLogs(sections, "Current task build diagnostics", taskBuildLogs, 20);
        appendBuildLogs(sections, "Recent build diagnostics", recentBuildLogs, 30);
        sections.add(new AiContextPackSection(
                AiContextPackSectionType.USAGE_RULE,
                "Auto-repair rules",
                "Use diagnostics only to locate the root cause of the current failure. Read and modify "
                        + "only directly relevant files; do not rebuild the whole project unless the validation "
                        + "plan requires it.",
                90,
                Map.of(
                        "authority", "system_context_rule",
                        "trust", "system_context_rule",
                        "source", "context_policy"
                )
        ));
        return contextPackBudgeter.apply(new AiContextPack(appId, "", "repair", sections));
    }

    private void appendSemanticMemories(List<AiContextPackSection> sections, List<SemanticMemoryHit> memories) {
        if (memories == null || memories.isEmpty()) {
            return;
        }
        int index = 0;
        for (SemanticMemoryHit hit : memories) {
            if (hit == null || hit.memory() == null || StrUtil.isBlank(hit.memory().content())) {
                continue;
            }
            index++;
            AiContextBoundaryService.ProtectedContext protectedMemory =
                    contextBoundaryService.protectHistoricalMemory(compact(hit.memory().content()));
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("memoryId", hit.memory().id());
            metadata.put("memoryType", hit.memory().type().name());
            metadata.put("score", hit.score());
            metadata.put("taskId", hit.memory().taskId());
            metadata.put("createdAt", hit.memory().createdAt());
            metadata.put("trust", "untrusted_history");
            metadata.put("source", "semantic_memory");
            metadata.put("sourceDigest", protectedMemory.digest());
            metadata.put("redacted", protectedMemory.redacted());
            metadata.put("sourceTruncated", protectedMemory.truncated());
            sections.add(new AiContextPackSection(
                    AiContextPackSectionType.SEMANTIC_MEMORY,
                    "Long-term semantic memory " + index,
                    "type=" + hit.memory().type().name().toLowerCase(Locale.ROOT)
                            + ", score=" + String.format(Locale.ROOT, "%.3f", hit.score())
                            + ", memoryId=" + hit.memory().id()
                            + "\n" + protectedMemory.content(),
                    20 + index,
                    metadata
            ));
        }
    }

    private void appendRecentTasks(List<AiContextPackSection> sections,
                                   List<GenerationTaskTrace> recentTasks,
                                   String userMessage) {
        if (recentTasks == null || recentTasks.isEmpty()) {
            return;
        }
        StringBuilder builder = new StringBuilder();
        for (GenerationTaskTrace task : recentTasks) {
            double relevance = relevanceScorer.score(userMessage, task.userPrompt());
            String relation = relevanceScorer.related(userMessage, task.userPrompt())
                    ? "related"
                    : "reference";
            builder.append("- [").append(relation)
                    .append(" relevance=")
                    .append(String.format(Locale.ROOT, "%.3f", relevance))
                    .append("] ")
                    .append(formatTime(task.createTime()))
                    .append(" taskId=").append(StrUtil.blankToDefault(task.taskId(), "unknown"))
                    .append(", status=").append(task.status() == null ? "unknown" : task.status().getValue());
            if (StrUtil.isNotBlank(task.memorySummary())) {
                builder.append(", summary=").append(compact(task.memorySummary())).append('\n');
                continue;
            }
            builder.append(", stage=").append(StrUtil.blankToDefault(task.stage(), "unknown"))
                    .append(", prompt=").append(compact(task.userPrompt()))
                    .append(formatTaskNote(task))
                    .append('\n');
        }
        appendProtectedEvidence(
                sections,
                AiContextPackSectionType.RECENT_TASK,
                "Recent generation tasks",
                builder.toString().trim(),
                30,
                "recent_task_trace",
                recentTasks.size()
        );
    }

    private void appendBuildLogs(List<AiContextPackSection> sections,
                                 String title,
                                 List<GenerationBuildTrace> buildLogs,
                                 int priority) {
        if (buildLogs == null || buildLogs.isEmpty()) {
            return;
        }
        StringBuilder builder = new StringBuilder();
        for (GenerationBuildTrace log : buildLogs) {
            builder.append("- ")
                    .append(formatTime(log.createTime()))
                    .append(" taskId=").append(StrUtil.blankToDefault(log.taskId(), "unknown"))
                    .append(", success=").append(log.success())
                    .append(", stage=").append(StrUtil.blankToDefault(log.stage(), "unknown"))
                    .append(", summary=").append(compact(log.summary()))
                    .append(formatReport(log.report()))
                    .append('\n');
        }
        appendProtectedEvidence(
                sections,
                AiContextPackSectionType.BUILD_TRACE,
                title,
                builder.toString().trim(),
                priority,
                "build_trace",
                buildLogs.size()
        );
    }

    private String resolveTargetType(App app, CodeGenTypeEnum targetType) {
        return targetType == null ? StrUtil.blankToDefault(app.getCodeGenType(), "unknown") : targetType.getValue();
    }

    private String formatTaskNote(GenerationTaskTrace task) {
        String note = task.status() == GenerationTaskStatus.RUNNING
                ? task.stageMessage()
                : task.errorMessage();
        return StrUtil.isBlank(note) ? "" : ", note=" + compact(note);
    }

    private void appendProtectedEvidence(List<AiContextPackSection> sections,
                                         AiContextPackSectionType sectionType,
                                         String title,
                                         String content,
                                         int priority,
                                         String source,
                                         int count) {
        if (StrUtil.isBlank(content)) {
            return;
        }
        AiContextBoundaryService.ProtectedContext protectedEvidence =
                contextBoundaryService.protectHistoricalEvidence(content, source);
        sections.add(new AiContextPackSection(
                sectionType,
                title,
                protectedEvidence.content(),
                priority,
                Map.of(
                        "count", count,
                        "trust", "untrusted_history",
                        "source", source,
                        "sourceDigest", protectedEvidence.digest(),
                        "redacted", protectedEvidence.redacted(),
                        "sourceTruncated", protectedEvidence.truncated()
                )
        ));
    }

    private String formatTime(LocalDateTime time) {
        return time == null ? "unknown_time" : TIME_FORMATTER.format(time);
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
