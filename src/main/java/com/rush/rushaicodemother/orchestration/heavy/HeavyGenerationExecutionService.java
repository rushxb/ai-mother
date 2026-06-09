package com.rush.rushaicodemother.orchestration.heavy;

import cn.hutool.core.util.StrUtil;
import com.rush.rushaicodemother.ai.model.GenerationPerformanceProfile;
import com.rush.rushaicodemother.ai.model.GenerationPerformanceSelector;
import com.rush.rushaicodemother.constant.AppConstant;
import com.rush.rushaicodemother.core.AiCodeGeneratorFacade;
import com.rush.rushaicodemother.core.error.GenerationErrorClassifier;
import com.rush.rushaicodemother.core.handler.GenerationStreamEvent;
import com.rush.rushaicodemother.core.handler.StreamHandlerExecutor;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.exception.ThrowUtils;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.monitor.GenerationOrchestrationMetricsCollector;
import com.rush.rushaicodemother.orchestration.GenerationAppStateService;
import com.rush.rushaicodemother.orchestration.GenerationPreparation;
import com.rush.rushaicodemother.orchestration.GenerationSession;
import com.rush.rushaicodemother.service.ChatHistoryService;
import com.rush.rushaicodemother.service.GenerationMemoryContextService;
import com.rush.rushaicodemother.service.impl.GeneratedProjectWorkspaceInspector;
import com.rush.rushaicodemother.service.impl.GenerationRepairPolicy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.io.File;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class HeavyGenerationExecutionService {

    private static final int MAX_GENERATION_SNAPSHOT_CHARS = 20000;
    private static final long GENERATION_SNAPSHOT_UPDATE_INTERVAL_MILLIS = 1000;

    private final AiCodeGeneratorFacade aiCodeGeneratorFacade;
    private final ChatHistoryService chatHistoryService;
    private final GenerationAppStateService generationAppStateService;
    private final GenerationMemoryContextService generationMemoryContextService;
    private final GenerationOrchestrationMetricsCollector generationOrchestrationMetricsCollector;
    private final GenerationPerformanceSelector generationPerformanceSelector;
    private final HeavyGenerationFailureRecoveryService heavyGenerationFailureRecoveryService;
    private final HeavyGenerationSessionCompletionService heavyGenerationSessionCompletionService;
    private final StreamHandlerExecutor streamHandlerExecutor;

    public void runGenerationWithAutoRepair(Long appId,
                                            User loginUser,
                                            GenerationPreparation preparation,
                                            GenerationSession session) {
        StringBuilder generatedContent = new StringBuilder();
        long[] lastSnapshotUpdateAt = {0L};
        String currentPrompt = preparation.enhancedMessage();
        Exception lastError = null;
        int maxGenerationRepairRounds = GenerationRepairPolicy.allowAutoRepair(
                preparation.generatingStage(),
                preparation.targetType(),
                HeavyGenerationLimits.MAX_AUTO_REPAIR_ROUNDS
        ) && preparation.requiresBuildValidation() ? HeavyGenerationLimits.MAX_AUTO_REPAIR_ROUNDS : 0;

        boolean isFirstGeneration = AppConstant.GENERATING_STAGE_CREATE.equals(preparation.generatingStage());
        boolean isComplex = isComplexPrompt(currentPrompt);
        GenerationPerformanceProfile profile = generationPerformanceSelector.select(
                isFirstGeneration, isComplex, preparation.targetType());

        for (int round = 0; round <= maxGenerationRepairRounds; round++) {
            session.throwIfCancelled();
            if (round > 0) {
                generationOrchestrationMetricsCollector.recordAutoRepair(orchestrationMode(preparation), "generation", "started");
                session.emit(GenerationStreamEvent.repairStart("\n\n[自动修复] 第 " + round + " 轮修复开始\n\n", Map.of(
                        "round", round,
                        "maxRounds", maxGenerationRepairRounds,
                        "taskId", preparation.taskId(),
                        "agent", "BuildFix"
                )));
                profile = GenerationPerformanceProfile.qualityFirst();
            }
            try {
                executeGenerationRound(appId, loginUser, preparation.targetType(), currentPrompt,
                        session, generatedContent, lastSnapshotUpdateAt, profile);
                if (round > 0) {
                    generationOrchestrationMetricsCollector.recordAutoRepair(orchestrationMode(preparation), "generation", "success");
                }
                return;
            } catch (Exception e) {
                lastError = e;
                GenerationErrorClassifier.GenerationError generationError =
                        heavyGenerationFailureRecoveryService.classifyGenerationError(e);
                log.warn("应用生成轮次失败，appId: {}, round: {}, category: {}, error: {}",
                        appId, round, generationError.category(), e.getMessage());
                if (round > 0) {
                    generationOrchestrationMetricsCollector.recordAutoRepair(orchestrationMode(preparation), "generation", "failed");
                }
                if (e instanceof MissingGeneratedProjectException || !generationError.recoverable()) {
                    break;
                }
                if (round >= maxGenerationRepairRounds) {
                    break;
                }
                currentPrompt = buildAutoRepairPrompt(appId, preparation, e, round + 1);
            }
        }
        throw new BusinessException(ErrorCode.SYSTEM_ERROR,
                lastError == null ? "生成失败" : StrUtil.blankToDefault(lastError.getMessage(), "生成失败"));
    }

    public void executeGenerationRound(Long appId,
                                       User loginUser,
                                       CodeGenTypeEnum codeGenType,
                                       String prompt,
                                       GenerationSession session,
                                       StringBuilder generatedContent,
                                       long[] lastSnapshotUpdateAt) {
        executeGenerationRound(appId, loginUser, codeGenType, prompt, session, generatedContent, lastSnapshotUpdateAt, null);
    }

    public void executeGenerationRound(Long appId,
                                       User loginUser,
                                       CodeGenTypeEnum codeGenType,
                                       String prompt,
                                       GenerationSession session,
                                       StringBuilder generatedContent,
                                       long[] lastSnapshotUpdateAt,
                                       GenerationPerformanceProfile profile) {
        Flux<GenerationStreamEvent> codeStream = aiCodeGeneratorFacade.generateAndSaveCodeStream(
                prompt, codeGenType, appId, session::isCancelled, session::setResponseHandle, profile);
        streamHandlerExecutor.doExecute(codeStream, chatHistoryService, appId, loginUser, codeGenType)
                .takeUntilOther(session.cancelSignal())
                .doOnNext(event -> {
                    session.throwIfCancelled();
                    appendGenerationSnapshotChunk(generatedContent, event.getText());
                    updateGenerationSnapshotIfDue(appId, generatedContent, lastSnapshotUpdateAt);
                    session.emit(event);
                })
                .doOnComplete(session::throwIfCancelled)
                .blockLast();
        verifyGeneratedProjectReady(appId, codeGenType);
    }

    public String buildAutoRepairPrompt(Long appId,
                                        GenerationPreparation preparation,
                                        Exception exception,
                                        int repairRound) {
        String errorMessage = StrUtil.blankToDefault(exception.getMessage(), "构建失败");
        String memoryContext = generationMemoryContextService.buildAutoRepairMemoryContext(
                appId,
                preparation == null ? null : preparation.taskId(),
                errorMessage,
                repairRound
        );
        String memorySection = StrUtil.isBlank(memoryContext) ? "" : "\n" + memoryContext + "\n";
        return """
                【自动修复任务】
                上一次 Vue 项目生成后未通过本地构建。请基于当前项目文件直接修复，不要重建整个项目。
                %s
                修复轮次：%d
                错误分类：%s
                错误摘要：
                %s

                必须遵守：
                1. 先使用项目搜索、目录读取或批量读取文件工具定位问题。
                2. 如果涉及依赖、scripts 或 package.json，先使用依赖问题分析工具，再用依赖与脚本管理工具处理。
                3. 只修改必要文件，避免无关重构。
                4. 修复后必须调用本地构建诊断工具验证。
                """.formatted(memorySection, repairRound,
                heavyGenerationFailureRecoveryService.classifyGenerationError(errorMessage).category(), errorMessage);
    }

    private boolean isComplexPrompt(String prompt) {
        if (StrUtil.isBlank(prompt)) {
            return false;
        }
        String normalized = prompt.toLowerCase();
        return normalized.contains("vue") || normalized.contains("组件") || normalized.contains("路由")
                || normalized.contains("模块") || normalized.contains("后台") || normalized.contains("管理系统")
                || normalized.contains("登录") || normalized.contains("注册") || normalized.contains("api")
                || normalized.contains("接口") || normalized.contains("状态管理") || normalized.contains("多页面")
                || normalized.contains("工作台") || normalized.contains("dashboard") || normalized.contains("crud");
    }

    private void verifyGeneratedProjectReady(Long appId, CodeGenTypeEnum codeGenType) {
        String projectPath = AppConstant.CODE_OUTPUT_ROOT_DIR + File.separator + codeGenType.getValue() + "_" + appId;
        if (codeGenType == CodeGenTypeEnum.BACKEND_PROJECT) {
            File projectDir = new File(projectPath);
            boolean ready = projectDir.isDirectory()
                    && new File(projectDir, "go.mod").isFile()
                    && new File(projectDir, "cmd/server/main.go").isFile();
            ThrowUtils.throwIf(!ready, ErrorCode.SYSTEM_ERROR, "生成结束但未发现有效后端工程，请重试生成");
            return;
        }
        if (codeGenType == CodeGenTypeEnum.FULL_STACK_PROJECT) {
            File projectDir = new File(projectPath);
            boolean ready = projectDir.isDirectory()
                    && new File(projectDir, "frontend/package.json").isFile()
                    && new File(projectDir, "backend/go.mod").isFile()
                    && new File(projectDir, "backend/cmd/server/main.go").isFile();
            ThrowUtils.throwIf(!ready, ErrorCode.SYSTEM_ERROR, "生成结束但未发现有效全栈工程，请重试生成");
            return;
        }
        if (codeGenType != CodeGenTypeEnum.VUE_PROJECT) {
            return;
        }
        GeneratedProjectWorkspaceInspector.WorkspaceState workspaceState =
                GeneratedProjectWorkspaceInspector.inspectVueProject(projectPath);
        if (!workspaceState.canAutoRepair()) {
            throw new MissingGeneratedProjectException(
                    heavyGenerationFailureRecoveryService.buildMissingProjectCodeMessage(workspaceState)
            );
        }
    }

    private void updateGenerationSnapshotIfDue(Long appId, StringBuilder generatedContent, long[] lastSnapshotUpdateAt) {
        long now = System.currentTimeMillis();
        if (now - lastSnapshotUpdateAt[0] < GENERATION_SNAPSHOT_UPDATE_INTERVAL_MILLIS) {
            return;
        }
        lastSnapshotUpdateAt[0] = now;
        generationAppStateService.updateGenerationSnapshot(appId, generatedContent.toString());
    }

    private void appendGenerationSnapshotChunk(StringBuilder generatedContent, String chunk) {
        if (chunk == null || chunk.isEmpty()) {
            return;
        }
        generatedContent.append(chunk);
        int overflowChars = generatedContent.length() - MAX_GENERATION_SNAPSHOT_CHARS;
        if (overflowChars > 0) {
            generatedContent.delete(0, overflowChars);
        }
    }

    private String orchestrationMode(GenerationPreparation preparation) {
        return heavyGenerationSessionCompletionService.orchestrationMode(preparation);
    }

    private static final class MissingGeneratedProjectException extends BusinessException {

        private MissingGeneratedProjectException(String message) {
            super(ErrorCode.SYSTEM_ERROR, message);
        }
    }
}
