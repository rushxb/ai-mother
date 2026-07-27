package com.rush.rushaicodemother.service.impl;

import com.rush.rushaicodemother.memory.GenerationSemanticMemoryService;
import com.rush.rushaicodemother.memory.MemoryType;
import com.rush.rushaicodemother.memory.SemanticMemoryHit;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.context.AiContextPack;
import com.rush.rushaicodemother.orchestration.context.AiContextPackAssembler;
import com.rush.rushaicodemother.orchestration.context.GenerationMemoryContextReadExecutor;
import com.rush.rushaicodemother.service.GenerationMemoryContextService;
import com.rush.rushaicodemother.service.trace.GenerationBuildTrace;
import com.rush.rushaicodemother.service.trace.GenerationTaskTrace;
import com.rush.rushaicodemother.service.trace.GenerationTraceService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

/**
 * 生成记忆上下文服务实现。
 */
@Service
public class GenerationMemoryContextServiceImpl implements GenerationMemoryContextService {

    private static final Set<MemoryType> GENERATION_MEMORY_TYPES = Set.of(
            MemoryType.TASK_OUTCOME,
            MemoryType.FAILURE_LESSON,
            MemoryType.USER_FEEDBACK,
            MemoryType.USER_PREFERENCE,
            MemoryType.ARCHITECTURE_DECISION
    );

    private final GenerationTraceService generationTraceService;
    private final GenerationSemanticMemoryService semanticMemoryService;
    private final AiContextPackAssembler contextPackAssembler;
    private final GenerationMemoryContextReadExecutor readExecutor;

    public GenerationMemoryContextServiceImpl(
            GenerationTraceService generationTraceService,
            GenerationSemanticMemoryService semanticMemoryService,
            AiContextPackAssembler contextPackAssembler
    ) {
        this(generationTraceService, semanticMemoryService, contextPackAssembler, null);
    }

    @Autowired
    public GenerationMemoryContextServiceImpl(
            GenerationTraceService generationTraceService,
            GenerationSemanticMemoryService semanticMemoryService,
            AiContextPackAssembler contextPackAssembler,
            GenerationMemoryContextReadExecutor readExecutor
    ) {
        this.generationTraceService = generationTraceService;
        this.semanticMemoryService = semanticMemoryService;
        this.contextPackAssembler = contextPackAssembler;
        this.readExecutor = readExecutor;
    }

    @Override
    public String buildGenerationMemoryContext(App app, String userMessage, CodeGenTypeEnum targetType) {
        if (app == null || app.getId() == null) {
            return "";
        }
        GenerationMemoryReads reads = readGenerationMemory(app, userMessage);
        AiContextPack contextPack = contextPackAssembler.buildGenerationPack(
                app,
                userMessage,
                targetType,
                reads.semanticMemories(),
                reads.recentTasks(),
                reads.recentBuildLogs()
        );
        return renderBudgeted(contextPack);
    }

    @Override
    public String buildAutoRepairMemoryContext(Long appId, String taskId, String errorMessage, int repairRound) {
        if (appId == null) {
            return "";
        }
        AutoRepairMemoryReads reads = readAutoRepairMemory(appId, taskId);
        AiContextPack contextPack = contextPackAssembler.buildAutoRepairPack(
                appId,
                taskId,
                errorMessage,
                repairRound,
                reads.taskBuildLogs(),
                reads.recentBuildLogs()
        );
        return renderBudgeted(contextPack);
    }

    private String renderBudgeted(AiContextPack contextPack) {
        if (contextPack == null || contextPack.empty()) {
            return "";
        }
        return contextPack.render();
    }

    private GenerationMemoryReads readGenerationMemory(App app, String userMessage) {
        if (readExecutor == null) {
            return readGenerationMemoryDirectly(app, userMessage);
        }
        if (!readExecutor.parallelReadsEnabled()) {
            return new GenerationMemoryReads(
                    readExecutor.readSequential("recent_tasks",
                            () -> generationTraceService.listRecentTasksByAppId(app.getId(), 5)),
                    readExecutor.readSequential("recent_build_logs",
                            () -> generationTraceService.listRecentBuildLogsByAppId(app.getId(), 3)),
                    readExecutor.readSequential("semantic_memory",
                            () -> recallSemanticMemory(app, userMessage))
            );
        }
        var recentTasks = readExecutor.task(
                "recent_tasks",
                () -> generationTraceService.listRecentTasksByAppId(app.getId(), 5));
        var recentBuildLogs = readExecutor.task(
                "recent_build_logs",
                () -> generationTraceService.listRecentBuildLogsByAppId(app.getId(), 3));
        var semanticMemories = readExecutor.task(
                "semantic_memory",
                () -> recallSemanticMemory(app, userMessage));
        readExecutor.executeFailFast(recentTasks, recentBuildLogs, semanticMemories);
        return new GenerationMemoryReads(
                recentTasks.result(),
                recentBuildLogs.result(),
                semanticMemories.result()
        );
    }

    private GenerationMemoryReads readGenerationMemoryDirectly(App app, String userMessage) {
        return new GenerationMemoryReads(
                generationTraceService.listRecentTasksByAppId(app.getId(), 5),
                generationTraceService.listRecentBuildLogsByAppId(app.getId(), 3),
                recallSemanticMemory(app, userMessage)
        );
    }

    private List<SemanticMemoryHit> recallSemanticMemory(App app, String userMessage) {
        return semanticMemoryService.recall(
                app.getTenantId(),
                app.getId(),
                userMessage,
                GENERATION_MEMORY_TYPES
        );
    }

    private AutoRepairMemoryReads readAutoRepairMemory(Long appId, String taskId) {
        if (readExecutor == null) {
            return new AutoRepairMemoryReads(
                    listTaskBuildLogs(taskId),
                    generationTraceService.listRecentBuildLogsByAppId(appId, 3)
            );
        }
        if (!readExecutor.parallelReadsEnabled()) {
            return new AutoRepairMemoryReads(
                    readExecutor.readSequential("task_build_logs", () -> listTaskBuildLogs(taskId)),
                    readExecutor.readSequential("recent_build_logs",
                            () -> generationTraceService.listRecentBuildLogsByAppId(appId, 3))
            );
        }
        var taskBuildLogs = readExecutor.task(
                "task_build_logs", () -> listTaskBuildLogs(taskId));
        var recentBuildLogs = readExecutor.task(
                "recent_build_logs", () -> generationTraceService.listRecentBuildLogsByAppId(appId, 3));
        readExecutor.executeFailFast(taskBuildLogs, recentBuildLogs);
        return new AutoRepairMemoryReads(taskBuildLogs.result(), recentBuildLogs.result());
    }

    private List<GenerationBuildTrace> listTaskBuildLogs(String taskId) {
        return taskId == null || taskId.isBlank()
                ? List.of()
                : generationTraceService.listBuildLogsByTaskId(taskId, 3);
    }

    private record GenerationMemoryReads(
            List<GenerationTaskTrace> recentTasks,
            List<GenerationBuildTrace> recentBuildLogs,
            List<SemanticMemoryHit> semanticMemories
    ) {
    }

    private record AutoRepairMemoryReads(
            List<GenerationBuildTrace> taskBuildLogs,
            List<GenerationBuildTrace> recentBuildLogs
    ) {
    }
}
