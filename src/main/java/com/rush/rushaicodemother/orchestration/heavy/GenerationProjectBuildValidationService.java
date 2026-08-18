package com.rush.rushaicodemother.orchestration.heavy;

import com.rush.rushaicodemother.core.builder.BuildExecutionBudgetReservation;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContextService;
import com.rush.rushaicodemother.orchestration.workspace.GeneratedProjectWorkspaceInspection;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 通过工程类型适配器注册表执行构建验证。 */
@Service
public class GenerationProjectBuildValidationService {

    private final Map<CodeGenTypeEnum, GenerationProjectBuildValidationAdapter> adaptersByType;
    private final GenerationExecutionContextService executionContextService;

    /**
     * 构建不可变工程类型适配器注册表，重复声明在启动阶段失败。
     *
     * @param adapters 工程类型构建验证适配器
     * @param executionContextService 生成执行上下文服务
     */
    public GenerationProjectBuildValidationService(
            List<GenerationProjectBuildValidationAdapter> adapters,
            GenerationExecutionContextService executionContextService
    ) {
        this.adaptersByType = GenerationProjectAdapterRegistry.register(adapters, "构建验证");
        this.executionContextService = Objects.requireNonNull(
                executionContextService, "生成执行上下文服务不能为空");
    }

    /** 使用注册适配器执行真实构建验证。 */
    public ProjectBuildValidationResult validate(
            GenerationWorkspace workspace,
            String taskId
    ) {
        Objects.requireNonNull(workspace, "生成工作区不能为空");
        CodeGenTypeEnum codeGenType = Objects.requireNonNull(
                workspace.codeGenType(), "生成工作区工程类型不能为空");
        BuildExecutionBudgetReservation budgetReservation =
                BuildExecutionBudgetReservation.forTask(executionContextService, taskId);
        return requireAdapter(codeGenType).validate(workspace, taskId, budgetReservation);
    }

    /** 复用同一工程 adapter 检查构建前工作区，不在编排主链重复工程类型分支。 */
    public GeneratedProjectWorkspaceInspection inspect(
            GenerationWorkspace workspace
    ) {
        Objects.requireNonNull(workspace, "生成工作区不能为空");
        CodeGenTypeEnum codeGenType = Objects.requireNonNull(
                workspace.codeGenType(), "生成工作区工程类型不能为空");
        GeneratedProjectWorkspaceInspection state =
                requireAdapter(codeGenType).inspect(workspace);
        if (state == null) {
            throw new IllegalStateException(
                    "工程构建验证适配器返回空工作区检查结果: " + codeGenType.getValue());
        }
        return state;
    }

    private GenerationProjectBuildValidationAdapter requireAdapter(CodeGenTypeEnum codeGenType) {
        GenerationProjectBuildValidationAdapter adapter = adaptersByType.get(codeGenType);
        if (adapter == null) {
            throw new IllegalArgumentException(
                    "当前项目类型不支持构建门禁: " + codeGenType.getValue());
        }
        return adapter;
    }

}
