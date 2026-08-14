package com.rush.rushaicodemother.orchestration.lifecycle;

import cn.hutool.core.util.StrUtil;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.model.enums.ChatHistoryMessageTypeEnum;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.model.enums.GenerationTaskStatus;
import com.rush.rushaicodemother.orchestration.GenerationAppStateService;
import com.rush.rushaicodemother.service.ChatHistoryService;
import com.rush.rushaicodemother.service.trace.GenerationOutcomeQuality;
import com.rush.rushaicodemother.service.trace.GenerationTaskStartCommand;
import com.rush.rushaicodemother.service.trace.GenerationTaskTraceStartResult;
import com.rush.rushaicodemother.service.trace.GenerationTraceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 生成路线的共享生命周期操作。
 */
@Service
@RequiredArgsConstructor
public class GenerationTaskLifecycleService {

    private final GenerationAppStateService generationAppStateService;
    private final ChatHistoryService chatHistoryService;
    private final GenerationTraceService generationTraceService;

    /**
 * 记录用户消息相关指标或状态。
 *
 * @param app 应用
 * @param user 用户
 * @param message 消息内容
 */
    public void recordUserMessage(App app, User user, String message) {
        if (app == null || app.getId() == null || user == null || user.getId() == null || StrUtil.isBlank(message)) {
            return;
        }
        recordUserMessage(app.getId(), user.getId(), message);
    }

    /**
 * 记录用户消息相关指标或状态。
 *
 * @param appId 应用编号
 * @param userId 用户编号
 * @param message 消息内容
 */
    public void recordUserMessage(Long appId, Long userId, String message) {
        if (appId == null || userId == null || StrUtil.isBlank(message)) {
            return;
        }
        chatHistoryService.addChatMessage(appId, message, ChatHistoryMessageTypeEnum.USER.getValue(), userId);
    }

    /**
 * 启动生成。
 *
 * @param taskId 任务编号
 * @param app 应用
 * @param user 用户
 * @param originalType {@code originalType} 对应的调用参数
 * @param targetType 目标类型
 * @param userPrompt 用户提示词
 * @param enhancedPrompt {@code enhancedPrompt} 对应的调用参数
 * @param requiresBuildValidation {@code requiresBuildValidation} 对应的调用参数
 * @param qualityGate 质量门禁
 * @param orchestrationMode 编排模式
 * @param generatingStage {@code generatingStage} 对应的调用参数
 */
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

    /**
 * 启动生成。
 *
 * @param taskId 任务编号
 * @param appId 应用编号
 * @param userId 用户编号
 * @param originalType {@code originalType} 对应的调用参数
 * @param targetType 目标类型
 * @param userPrompt 用户提示词
 * @param enhancedPrompt {@code enhancedPrompt} 对应的调用参数
 * @param requiresBuildValidation {@code requiresBuildValidation} 对应的调用参数
 * @param qualityGate 质量门禁
 * @param orchestrationMode 编排模式
 * @param generatingStage {@code generatingStage} 对应的调用参数
 */
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

    /**
 * 启动{@code Or}状态转换生成。
 *
 * @param taskId 任务编号
 * @param appId 应用编号
 * @param userId 用户编号
 * @param originalType {@code originalType} 对应的调用参数
 * @param targetType 目标类型
 * @param userPrompt 用户提示词
 * @param enhancedPrompt {@code enhancedPrompt} 对应的调用参数
 * @param requiresBuildValidation {@code requiresBuildValidation} 对应的调用参数
 * @param qualityGate 质量门禁
 * @param orchestrationMode 编排模式
 * @param generatingStage {@code generatingStage} 对应的调用参数
 */
    @Transactional(rollbackFor = Exception.class)
    public void startOrTransitionGeneration(String taskId,
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
        GenerationTaskTraceStartResult startResult = generationTraceService.startOrTransitionTask(
                new GenerationTaskStartCommand(
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
                )
        );
        if (startResult.shouldRecordUserMessage()) {
            recordUserMessage(appId, userId, userPrompt);
        }
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

    /**
 * 更新生成阶段。
 *
 * @param taskId 任务编号
 * @param appId 应用编号
 * @param generatingStage {@code generatingStage} 对应的调用参数
 * @param generatingMessage {@code generatingMessage} 对应的调用参数
 */
    @Transactional(rollbackFor = Exception.class)
    public void updateGenerationStage(String taskId,
                                      Long appId,
                                      String generatingStage,
                                      String generatingMessage) {
        generationAppStateService.updateOwnedGenerationStage(
                appId, taskId, generatingStage, generatingMessage);
        generationTraceService.updateStage(taskId, generatingStage, generatingMessage);
    }

    /**
     * 在一个数据库事务中提交生成业务终态并释放应用所有权。
     *
     * <p>该方法只由 {@code GenerationTaskFinalizer} 调用。执行轮次显式传入，避免旧 worker
     * 在终态收口时释放新一轮任务的应用状态。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean finalizeGeneration(String taskId,
                                      Long appId,
                                      Long executionEpoch,
                                      GenerationTaskStatus status,
                                      String errorMessage,
                                      String memorySummary,
                                      GenerationOutcomeQuality outcomeQuality) {
        generationAppStateService.lockGenerationState(appId);
        if (memorySummary != null) {
            generationTraceService.updateMemorySummary(taskId, memorySummary);
        }
        boolean released = executionEpoch == null
                ? generationAppStateService.releaseOwnedGenerationState(appId, taskId)
                : generationAppStateService.releaseOwnedGenerationState(appId, taskId, executionEpoch);
        if (outcomeQuality == null || outcomeQuality.isEmpty()) {
            generationTraceService.completeTask(taskId, status, errorMessage);
        } else {
            generationTraceService.completeTask(taskId, status, errorMessage, outcomeQuality);
        }
        return released;
    }

}
