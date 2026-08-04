package com.rush.rushaicodemother.orchestration;

import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.entity.User;

/**
 * 生成任务请求参数。
 */
public record GenerationTaskRequest(
        App app,
        String message,
        User loginUser,
        GenerationResourceRequirements resourceRequirements
) {

    public GenerationTaskRequest {
        if (resourceRequirements == null) {
            resourceRequirements = GenerationResourceRequirements.none();
        }
    }

    /** 保留不含资源需求的兼容构造器。 */
    public GenerationTaskRequest(App app, String message, User loginUser) {
        this(app, message, loginUser, GenerationResourceRequirements.none());
    }
}
