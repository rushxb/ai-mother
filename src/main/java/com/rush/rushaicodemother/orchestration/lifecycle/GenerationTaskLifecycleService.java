package com.rush.rushaicodemother.orchestration.lifecycle;

import cn.hutool.core.util.StrUtil;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.model.enums.ChatHistoryMessageTypeEnum;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.model.enums.GenerationTaskStatus;
import com.rush.rushaicodemother.orchestration.GenerationAppStateService;
import com.rush.rushaicodemother.service.ChatHistoryService;
import com.rush.rushaicodemother.service.UserCreditService;
import com.rush.rushaicodemother.service.trace.GenerationTaskStartCommand;
import com.rush.rushaicodemother.service.trace.GenerationTraceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Shared lifecycle operations for generation routes.
 */
@Service
@RequiredArgsConstructor
public class GenerationTaskLifecycleService {

    private final GenerationAppStateService generationAppStateService;
    private final ChatHistoryService chatHistoryService;
    private final GenerationTraceService generationTraceService;
    private final UserCreditService userCreditService;

    public void recordUserMessage(App app, User user, String message) {
        if (app == null || app.getId() == null || user == null || user.getId() == null || StrUtil.isBlank(message)) {
            return;
        }
        recordUserMessage(app.getId(), user.getId(), message);
    }

    public void recordUserMessage(Long appId, Long userId, String message) {
        if (appId == null || userId == null || StrUtil.isBlank(message)) {
            return;
        }
        chatHistoryService.addChatMessage(appId, message, ChatHistoryMessageTypeEnum.USER.getValue(), userId);
    }

    @Transactional(rollbackFor = Exception.class)
    public void startGeneration(String taskId,
                                App app,
                                User user,
                                CodeGenTypeEnum originalType,
                                CodeGenTypeEnum targetType,
                                String userPrompt,
                                String enhancedPrompt,
                                boolean requiresBuildValidation,
                                String qualityGate,
                                String orchestrationMode,
                                String generatingStage) {
        startGeneration(
                taskId,
                app == null ? null : app.getId(),
                user == null ? null : user.getId(),
                originalType,
                targetType,
                userPrompt,
                enhancedPrompt,
                requiresBuildValidation,
                qualityGate,
                orchestrationMode,
                generatingStage
        );
    }

    @Transactional(rollbackFor = Exception.class)
    public void startGeneration(String taskId,
                                Long appId,
                                Long userId,
                                CodeGenTypeEnum originalType,
                                CodeGenTypeEnum targetType,
                                String userPrompt,
                                String enhancedPrompt,
                                boolean requiresBuildValidation,
                                String qualityGate,
                                String orchestrationMode,
                                String generatingStage) {
        generationAppStateService.claimGenerationState(appId, taskId, generatingStage, targetType);
        startTrace(
                taskId, appId, userId, originalType, targetType, userPrompt, enhancedPrompt,
                requiresBuildValidation, qualityGate, orchestrationMode
        );
    }

    private void startTrace(String taskId,
                            Long appId,
                            Long userId,
                            CodeGenTypeEnum originalType,
                            CodeGenTypeEnum targetType,
                            String userPrompt,
                            String enhancedPrompt,
                            boolean requiresBuildValidation,
                            String qualityGate,
                            String orchestrationMode) {
        generationTraceService.startTask(new GenerationTaskStartCommand(
                taskId, appId, userId, originalType, targetType,
                userPrompt, enhancedPrompt, requiresBuildValidation,
                qualityGate, orchestrationMode
        ));
    }

    @Transactional(rollbackFor = Exception.class)
    public void updateGenerationStage(String taskId,
                                      Long appId,
                                      String generatingStage,
                                      String generatingMessage) {
        generationAppStateService.updateOwnedGenerationStage(
                appId, taskId, generatingStage, generatingMessage);
        generationTraceService.updateStage(taskId, generatingStage, generatingMessage);
    }


    @Transactional(rollbackFor = Exception.class)
    public boolean completeGeneration(String taskId,
                                      Long appId,
                                      GenerationTaskStatus status,
                                      String errorMessage) {
        boolean released = generationAppStateService.releaseOwnedGenerationState(appId, taskId);
        generationTraceService.completeTask(taskId, status, errorMessage);
        return released;
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean completeGenerationAndCharge(String taskId,
                                               Long appId,
                                               GenerationTaskStatus status,
                                               String errorMessage) {
        boolean released = generationAppStateService.releaseOwnedGenerationState(appId, taskId);
        generationTraceService.completeTask(taskId, status, errorMessage);
        userCreditService.chargeGenerationTask(taskId);
        return released;
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean completeGenerationAndCharge(String taskId,
                                               Long appId,
                                               GenerationTaskStatus status,
                                               String errorMessage,
                                               String memorySummary) {
        generationTraceService.updateMemorySummary(taskId, memorySummary);
        return completeGenerationAndCharge(taskId, appId, status, errorMessage);
    }

    public boolean releaseGenerationState(String taskId, Long appId) {
        return generationAppStateService.releaseOwnedGenerationState(appId, taskId);
    }


}
