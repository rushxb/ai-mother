package com.rush.rushaicodemother.orchestration.heavy;

import com.rush.rushaicodemother.core.builder.BuildExecutionBudgetReservation;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContextService;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import org.springframework.stereotype.Service;

import java.util.EnumMap;
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
        this.adaptersByType = registerAdapters(adapters);
        this.executionContextService = Objects.requireNonNull(
                executionContextService, "生成执行上下文服务不能为空");
    }

    /** 使用注册适配器执行真实构建验证。 */
    public ProjectBuildValidationResult validate(
            GenerationWorkspace workspace,
            CodeGenTypeEnum codeGenType,
            String taskId
    ) {
        Objects.requireNonNull(workspace, "生成工作区不能为空");
        Objects.requireNonNull(codeGenType, "代码生成类型不能为空");
        BuildExecutionBudgetReservation budgetReservation =
                BuildExecutionBudgetReservation.forTask(executionContextService, taskId);
        return requireAdapter(codeGenType).validate(workspace, taskId, budgetReservation);
    }

    private GenerationProjectBuildValidationAdapter requireAdapter(CodeGenTypeEnum codeGenType) {
        GenerationProjectBuildValidationAdapter adapter = adaptersByType.get(codeGenType);
        if (adapter == null) {
            throw new IllegalArgumentException(
                    "当前项目类型不支持构建门禁: " + codeGenType.getValue());
        }
        return adapter;
    }

    private static Map<CodeGenTypeEnum, GenerationProjectBuildValidationAdapter> registerAdapters(
            List<GenerationProjectBuildValidationAdapter> adapters
    ) {
        if (adapters == null || adapters.isEmpty()) {
            throw new IllegalStateException("至少需要注册一个工程构建验证适配器");
        }
        EnumMap<CodeGenTypeEnum, GenerationProjectBuildValidationAdapter> registered =
                new EnumMap<>(CodeGenTypeEnum.class);
        for (GenerationProjectBuildValidationAdapter adapter : adapters) {
            if (adapter == null) {
                throw new IllegalStateException("工程构建验证适配器必须声明工程类型");
            }
            CodeGenTypeEnum codeGenType = adapter.codeGenType();
            if (codeGenType == null) {
                throw new IllegalStateException("工程构建验证适配器必须声明工程类型");
            }
            GenerationProjectBuildValidationAdapter previous = registered.putIfAbsent(
                    codeGenType, adapter);
            if (previous != null) {
                throw new IllegalStateException(
                        "工程类型存在重复构建验证适配器: " + codeGenType.getValue());
            }
        }
        return Map.copyOf(registered);
    }
}
