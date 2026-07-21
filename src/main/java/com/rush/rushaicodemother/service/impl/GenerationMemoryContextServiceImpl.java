package com.rush.rushaicodemother.service.impl;

import com.rush.rushaicodemother.memory.GenerationSemanticMemoryService;
import com.rush.rushaicodemother.memory.MemoryType;
import com.rush.rushaicodemother.memory.SemanticMemoryHit;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.context.AiContextPack;
import com.rush.rushaicodemother.orchestration.context.AiContextPackAssembler;
import com.rush.rushaicodemother.service.GenerationMemoryContextService;
import com.rush.rushaicodemother.service.trace.GenerationBuildTrace;
import com.rush.rushaicodemother.service.trace.GenerationTaskTrace;
import com.rush.rushaicodemother.service.trace.GenerationTraceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
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

    @Override
    public String buildGenerationMemoryContext(App app, String userMessage, CodeGenTypeEnum targetType) {
        if (app == null || app.getId() == null) {
            return "";
        }
        List<GenerationTaskTrace> recentTasks = generationTraceService.listRecentTasksByAppId(app.getId(), 5);
        List<GenerationBuildTrace> recentBuildLogs = generationTraceService.listRecentBuildLogsByAppId(app.getId(), 3);
        List<SemanticMemoryHit> semanticMemories = semanticMemoryService.recall(
                app.getTenantId(),
                app.getId(),
                userMessage,
                GENERATION_MEMORY_TYPES
        );
        AiContextPack contextPack = contextPackAssembler.buildGenerationPack(
                app,
                userMessage,
                targetType,
                semanticMemories,
                recentTasks,
                recentBuildLogs
        );
        return renderBudgeted(contextPack);
    }

    @Override
    public String buildAutoRepairMemoryContext(Long appId, String taskId, String errorMessage, int repairRound) {
        if (appId == null) {
            return "";
        }
        List<GenerationBuildTrace> taskBuildLogs = taskId == null || taskId.isBlank()
                ? List.of()
                : generationTraceService.listBuildLogsByTaskId(taskId, 3);
        List<GenerationBuildTrace> recentBuildLogs = generationTraceService.listRecentBuildLogsByAppId(appId, 3);
        AiContextPack contextPack = contextPackAssembler.buildAutoRepairPack(
                appId,
                taskId,
                errorMessage,
                repairRound,
                taskBuildLogs,
                recentBuildLogs
        );
        return renderBudgeted(contextPack);
    }

    private String renderBudgeted(AiContextPack contextPack) {
        if (contextPack == null || contextPack.empty()) {
            return "";
        }
        return contextPack.render();
    }
}
