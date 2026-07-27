package com.rush.rushaicodemother.orchestration;

import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.entity.User;

/**
 * 生成任务请求参数。
 */
public record GenerationTaskRequest(
        App app,
        String message,
        User loginUser
) {
}
