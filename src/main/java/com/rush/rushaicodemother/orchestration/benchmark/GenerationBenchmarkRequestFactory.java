package com.rush.rushaicodemother.orchestration.benchmark;

import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.orchestration.GenerationTaskRequest;
import org.springframework.stereotype.Component;

/**
 * 生成基准测试请求对象工厂。
 */
@Component
public class GenerationBenchmarkRequestFactory {

    public GenerationTaskRequest create(GenerationBenchmarkTask task, App app, User user) {
        if (task == null || app == null || app.getId() == null || app.getId() <= 0
                || user == null || user.getId() == null || user.getId() <= 0) {
            throw new IllegalArgumentException("benchmark task, app and user must be persisted");
        }
        return new GenerationTaskRequest(app, task.prompt(), user);
    }

}
