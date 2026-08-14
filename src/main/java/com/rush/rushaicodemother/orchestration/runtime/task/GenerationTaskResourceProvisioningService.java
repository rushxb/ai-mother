package com.rush.rushaicodemother.orchestration.runtime.task;

import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.orchestration.GenerationResourceRequirements;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContext;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.GenerationTaskCommand;
import com.rush.rushaicodemother.service.AppDatabaseResourceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * 在任务工作器持有有效执行租约时，幂等准备生成所需的应用资源。
 *
 * <p>该模块只负责资源准备，不拥有任务终态、调度或工作区生命周期。资源服务自身必须
 * 提供幂等语义，以便任务恢复或租约转移后安全重试。</p>
 */
@Service
@RequiredArgsConstructor
public class GenerationTaskResourceProvisioningService {

    private final AppDatabaseResourceService appDatabaseResourceService;

    /** 按持久化命令准备资源；无资源需求时直接返回。 */
    public void provision(GenerationTaskCommand command,
                          App app,
                          GenerationExecutionContext executionContext) {
        Objects.requireNonNull(command, "生成任务命令不能为空");
        Objects.requireNonNull(app, "应用不能为空");
        Objects.requireNonNull(executionContext, "生成执行上下文不能为空");

        GenerationResourceRequirements requirements = command.scenarioDecision().requiredResources();
        if (requirements == null || !requirements.databaseRequired()) {
            return;
        }

        // 副作用前后均校验租约、取消与截止时间，避免失去所有权后继续修改应用资源。
        executionContext.assertCanContinue();
        appDatabaseResourceService.enableDatabase(app);
        executionContext.assertCanContinue();
    }
}
