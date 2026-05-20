package com.rush.rushaicodemother.orchestration;

import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.entity.User;

public record GenerationTaskRequest(
        App app,
        String message,
        User loginUser
) {
}
