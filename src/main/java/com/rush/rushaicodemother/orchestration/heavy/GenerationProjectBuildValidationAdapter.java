package com.rush.rushaicodemother.orchestration.heavy;

import com.rush.rushaicodemother.core.builder.BuildExecutionBudgetReservation;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;

/**
 * 工程类型的构建验证适配器。
 *
 * <p>每种可构建工程类型必须独占一个适配器，使新增类型不再修改构建验证主干。</p>
 */
public interface GenerationProjectBuildValidationAdapter {

    /** 返回当前适配器负责的工程类型。 */
    CodeGenTypeEnum codeGenType();

    /** 使用本轮共享预算执行真实构建验证。 */
    ProjectBuildValidationResult validate(
            GenerationWorkspace workspace,
            String taskId,
            BuildExecutionBudgetReservation budgetReservation
    );
}
