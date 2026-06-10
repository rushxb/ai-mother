package com.rush.rushaicodemother.orchestration.lifecycle;

import cn.hutool.core.util.StrUtil;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.model.enums.ChatHistoryMessageTypeEnum;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.GenerationPreparation;
import com.rush.rushaicodemother.orchestration.GenerationSession;
import com.rush.rushaicodemother.service.ChatHistoryService;
import com.rush.rushaicodemother.service.GenerationTraceService;
import com.rush.rushaicodemother.service.UserCreditService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * Shared lifecycle operations for generation routes.
 */
@Service
@RequiredArgsConstructor
public class GenerationTaskLifecycleService {

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

    public void startTrace(String taskId,
                           App app,
                           User user,
                           CodeGenTypeEnum originalType,
                           CodeGenTypeEnum targetType,
                           String userPrompt,
                           String enhancedPrompt,
                           boolean requiresBuildValidation,
                           String qualityGate,
                           String orchestrationMode) {
        generationTraceService.startTask(
                taskId,
                app == null ? null : app.getId(),
                user == null ? null : user.getId(),
                originalType,
                targetType,
                userPrompt,
                enhancedPrompt,
                requiresBuildValidation,
                qualityGate,
                orchestrationMode
        );
    }

    public void startTrace(String taskId,
                           Long appId,
                           Long userId,
                           CodeGenTypeEnum originalType,
                           CodeGenTypeEnum targetType,
                           String userPrompt,
                           String enhancedPrompt,
                           boolean requiresBuildValidation,
                           String qualityGate,
                           String orchestrationMode) {
        generationTraceService.startTask(
                taskId,
                appId,
                userId,
                originalType,
                targetType,
                userPrompt,
                enhancedPrompt,
                requiresBuildValidation,
                qualityGate,
                orchestrationMode
        );
    }

    public void startTrace(App app, User user, String userPrompt, GenerationPreparation preparation) {
        startTrace(
                preparation.taskId(),
                app,
                user,
                preparation.originalType(),
                preparation.targetType(),
                userPrompt,
                preparation.enhancedMessage(),
                preparation.requiresBuildValidation(),
                preparation.qualityGateLevel(),
                orchestrationMode(preparation)
        );
    }

    public void completeTrace(String taskId, String status, Instant startedAt, String errorMessage) {
        generationTraceService.completeTask(taskId, status, startedAt, errorMessage);
    }

    public void completeTrace(GenerationSession session, GenerationPreparation preparation, String status, String errorMessage) {
        if (preparation == null) {
            return;
        }
        generationTraceService.completeTask(preparation.taskId(), status, session == null ? null : session.startedAt(), errorMessage);
    }

    public void updateMemorySummary(String taskId, String memorySummary) {
        generationTraceService.updateMemorySummary(taskId, memorySummary);
    }

    public void charge(String taskId) {
        userCreditService.chargeGenerationTask(taskId);
    }

    private String orchestrationMode(GenerationPreparation preparation) {
        if (preparation == null || preparation.events() == null) {
            return "unknown";
        }
        return preparation.events().stream()
                .map(event -> event.getData() == null ? null : event.getData().get("orchestrationMode"))
                .filter(value -> value != null && StrUtil.isNotBlank(String.valueOf(value)))
                .map(String::valueOf)
                .findFirst()
                .orElse("unknown");
    }
}
