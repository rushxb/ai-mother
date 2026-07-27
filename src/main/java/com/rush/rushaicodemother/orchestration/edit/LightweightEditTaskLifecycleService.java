package com.rush.rushaicodemother.orchestration.edit;

import com.rush.rushaicodemother.constant.AppConstant;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.lifecycle.GenerationTaskLifecycleService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 确保轻量级编辑应用程序状态、跟踪状态和充电保持一致。 */
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

}
