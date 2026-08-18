package com.rush.rushaicodemother.orchestration.create;

import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import org.springframework.stereotype.Service;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * CREATE 模板规划入口。
 *
 * <p>该 module 只维护工程类型到 adapter 的唯一映射。具体模板、功能模块和 slot
 * 规则由对应 adapter 持有，新增工程类型不再修改此处。</p>
 */
@Service
public class CreateTemplatePlanner {

    private final Map<CodeGenTypeEnum, CreateTemplatePlanningAdapter> adaptersByType;

    /** 构建不可变 adapter 注册表，并在启动阶段拒绝含糊配置。 */
    public CreateTemplatePlanner(List<CreateTemplatePlanningAdapter> adapters) {
        if (adapters == null) {
            throw new IllegalStateException("CREATE 模板规划适配器列表不能为空");
        }
        if (adapters.isEmpty()) {
            throw new IllegalStateException("至少需要注册一个 CREATE 模板规划适配器");
        }
        EnumMap<CodeGenTypeEnum, CreateTemplatePlanningAdapter> registeredAdapters =
                new EnumMap<>(CodeGenTypeEnum.class);
        for (CreateTemplatePlanningAdapter adapter : adapters) {
            registerAdapter(registeredAdapters, adapter);
        }
        this.adaptersByType = Map.copyOf(registeredAdapters);
    }

    /** 为指定工程类型生成模板计划；未注册类型返回可诊断的不支持结果。 */
    public CreateGenerationPlan plan(CodeGenTypeEnum codeGenType, String userMessage) {
        if (codeGenType == null) {
            return unsupported(null, "代码生成类型为空");
        }
        CreateTemplatePlanningAdapter adapter = adaptersByType.get(codeGenType);
        if (adapter == null) {
            return unsupported(codeGenType, "CREATE 模板运行时暂不覆盖该代码类型");
        }
        CreateGenerationPlan plan = adapter.plan(userMessage);
        if (plan == null) {
            throw new IllegalStateException(
                    "CREATE 模板规划适配器返回空计划: " + codeGenType.getValue());
        }
        if (plan.codeGenType() != codeGenType) {
            throw new IllegalStateException(
                    "CREATE 模板规划适配器返回了错误工程类型: " + codeGenType.getValue());
        }
        return plan;
    }

    private static void registerAdapter(
            Map<CodeGenTypeEnum, CreateTemplatePlanningAdapter> registeredAdapters,
            CreateTemplatePlanningAdapter adapter
    ) {
        if (adapter == null) {
            throw new IllegalStateException("CREATE 模板规划适配器列表不能包含 null");
        }
        CodeGenTypeEnum codeGenType = adapter.codeGenType();
        if (codeGenType == null) {
            throw new IllegalStateException(
                    "CREATE 模板规划适配器必须声明工程类型: " + adapter.getClass().getName());
        }
        CreateTemplatePlanningAdapter previous = registeredAdapters.putIfAbsent(codeGenType, adapter);
        if (previous != null) {
            throw new IllegalStateException("工程类型存在重复 CREATE 模板规划适配器: " + codeGenType.getValue());
        }
    }

    private CreateGenerationPlan unsupported(CodeGenTypeEnum codeGenType, String reason) {
        return new CreateGenerationPlan(
                codeGenType,
                null,
                List.of(),
                List.of(),
                0,
                reason,
                "local_rules",
                "template_coverage_missing"
        );
    }
}
