package com.rush.rushaicodemother.orchestration.benchmark;

import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.orchestration.GenerationPlanningVariant;
import com.rush.rushaicodemother.orchestration.GenerationResourceRequirements;
import com.rush.rushaicodemother.orchestration.GenerationTaskRequest;
import org.springframework.stereotype.Component;

/**
 * 生成基准测试请求对象工厂。
 */
@Component
public class GenerationBenchmarkRequestFactory {

    /**
 * 创建生成基准测试请求。
 *
 * @param task 任务
 * @param app 应用
 * @param user 用户
 * @return 生成基准测试请求
 */
    public GenerationTaskRequest create(GenerationBenchmarkTask task,
                                        App app,
                                        User user,
                                        GenerationPlanningVariant planningVariant) {
        if (task == null || app == null || app.getId() == null || app.getId() <= 0
                || user == null || user.getId() == null || user.getId() <= 0) {
            throw new IllegalArgumentException("benchmark task, app and user must be persisted");
        }
        return new GenerationTaskRequest(
                app,
                task.prompt(),
                user,
                GenerationResourceRequirements.none(),
                planningVariant
        );
    }

    public GenerationTaskRequest create(GenerationBenchmarkTask task, App app, User user) {
        return create(task, app, user,
                GenerationPlanningVariant.CURRENT_DAG);
    }

}
