package com.rush.rushaicodemother.orchestration.edit;

import com.rush.rushaicodemother.constant.AppConstant;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.model.enums.GenerationTaskStatus;
import com.rush.rushaicodemother.orchestration.lifecycle.GenerationTaskLifecycleService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Guarantees that lightweight-edit application state, trace state and charging remain consistent. */
@Service
@RequiredArgsConstructor
public class LightweightEditTaskLifecycleService {

    private final GenerationTaskLifecycleService generationTaskLifecycleService;

    public void start(String taskId,
                      App app,
                      User user,
                      CodeGenTypeEnum codeGenType,
                      String userMessage,
                      boolean requiresBuild) {
        generationTaskLifecycleService.recordUserMessage(app, user, userMessage);
        generationTaskLifecycleService.startGeneration(
                taskId,
                app,
                user,
                codeGenType,
                codeGenType,
                userMessage,
                userMessage,
                requiresBuild,
                "lightweight",
                "lightweight_edit",
                AppConstant.GENERATING_STAGE_UPDATE
        );
    }

    public void completeSuccess(String taskId, Long appId) {
        generationTaskLifecycleService.completeGenerationAndCharge(
                taskId, appId, GenerationTaskStatus.SUCCESS, null);
    }

    public void completeFailure(String taskId, Long appId, String reason) {
        generationTaskLifecycleService.completeGeneration(
                taskId, appId, GenerationTaskStatus.FAILED, reason);
    }
}
