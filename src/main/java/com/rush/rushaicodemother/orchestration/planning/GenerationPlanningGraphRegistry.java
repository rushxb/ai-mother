package com.rush.rushaicodemother.orchestration.planning;

import com.rush.rushaicodemother.orchestration.GenerationPlanningVariant;
import com.rush.rushaicodemother.orchestration.dag.GenerationAgentNode;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 按强类型规划方案解析唯一 DAG 图适配器。 */
@Component
public final class GenerationPlanningGraphRegistry {

    private final Map<GenerationPlanningVariant, GenerationPlanningGraphAdapter> adapters;

    public GenerationPlanningGraphRegistry(List<GenerationPlanningGraphAdapter> adapters) {
        Objects.requireNonNull(adapters, "规划图适配器不能为空");
        EnumMap<GenerationPlanningVariant, GenerationPlanningGraphAdapter> registered =
                new EnumMap<>(GenerationPlanningVariant.class);
        for (GenerationPlanningGraphAdapter adapter : adapters) {
            GenerationPlanningGraphAdapter required = Objects.requireNonNull(
                    adapter, "规划图适配器不能包含空值");
            GenerationPlanningVariant variant = Objects.requireNonNull(
                    required.variant(), "规划图适配器必须声明支持方案");
            if (registered.putIfAbsent(variant, required) != null) {
                throw new IllegalStateException("规划图适配器重复注册: " + variant);
            }
        }
        EnumSet<GenerationPlanningVariant> missing =
                EnumSet.allOf(GenerationPlanningVariant.class);
        missing.removeAll(registered.keySet());
        if (!missing.isEmpty()) {
            throw new IllegalStateException("规划图适配器注册不完整: " + missing);
        }
        this.adapters = Map.copyOf(registered);
    }

    /** 返回指定方案的不可变节点图。 */
    public List<GenerationAgentNode> resolve(GenerationPlanningVariant variant,
                                             boolean heavyPath) {
        GenerationPlanningGraphAdapter adapter = adapters.get(
                Objects.requireNonNull(variant, "规划方案不能为空"));
        if (adapter == null) {
            throw new IllegalArgumentException("未注册规划图适配器: " + variant);
        }
        return List.copyOf(adapter.nodes(heavyPath));
    }
}
