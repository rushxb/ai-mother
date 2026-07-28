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

    /**
 * 启动轻量编辑任务生命周期。
 *
 * @param taskId 任务编号
 * @param app 应用
 * @param user 用户
 * @param codeGenType 代码生成类型
 * @param userMessage 用户消息
 * @param requiresBuild {@code requiresBuild} 对应的调用参数
 */
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
