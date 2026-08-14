package com.rush.rushaicodemother.orchestration.pipeline;

import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.decision.GenerationMutability;
import com.rush.rushaicodemother.orchestration.decision.GenerationScenarioDecision;
import com.rush.rushaicodemother.orchestration.intent.IntentOperationType;
import com.rush.rushaicodemother.orchestration.router.GenerationMode;

import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * 一条 pipeline 的静态执行契约。
 *
 * <p>静态能力不读取工作区或原始 Prompt，因此可同时供提交准入与 worker 路由使用。
 * 运行时条件仍可由 pipeline 在 {@code supports} 中追加，但不得放宽本契约。</p>
 */
public record GenerationPipelineCapability(
        String route,
        GenerationMutability mutability,
        Set<IntentOperationType> operations,
        Set<CodeGenTypeEnum> targetTypes,
        Set<GenerationMode> modes
) {

    public GenerationPipelineCapability {
        route = requireRoute(route);
        Objects.requireNonNull(mutability, "能力可变性不能为空");
        operations = immutableEnumSet(operations, "能力操作集合不能为空");
        targetTypes = immutableEnumSet(targetTypes, "能力工程类型集合不能为空");
        modes = immutableEnumSet(modes, "能力模式集合不能为空");
        validateSemanticAlignment(mutability, operations, modes);
    }

    public static GenerationPipelineCapability write(
            String route,
            Set<IntentOperationType> operations,
            Set<CodeGenTypeEnum> targetTypes,
            Set<GenerationMode> modes
    ) {
        return new GenerationPipelineCapability(
                route, GenerationMutability.WRITE, operations, targetTypes, modes);
    }

    public static GenerationPipelineCapability readOnly(
            String route,
            Set<IntentOperationType> operations,
            Set<CodeGenTypeEnum> targetTypes,
            Set<GenerationMode> modes
    ) {
        return new GenerationPipelineCapability(
                route, GenerationMutability.READ_ONLY, operations, targetTypes, modes);
    }

    /** 判断冻结场景是否落入本 pipeline 的静态能力边界。 */
    public boolean supports(GenerationScenarioDecision decision) {
        return decision != null
                && operations.contains(decision.operation())
                && mutability == decision.mutability()
                && targetTypes.contains(decision.targetType())
                && modes.contains(decision.routeDecision().mode());
    }

    Set<GenerationPipelineCapabilityKey> keys() {
        Set<GenerationPipelineCapabilityKey> keys = new LinkedHashSet<>();
        for (IntentOperationType operation : operations) {
            for (CodeGenTypeEnum targetType : targetTypes) {
                keys.add(new GenerationPipelineCapabilityKey(operation, mutability, targetType));
            }
        }
        return Collections.unmodifiableSet(keys);
    }

    private static void validateSemanticAlignment(
            GenerationMutability mutability,
            Set<IntentOperationType> operations,
            Set<GenerationMode> modes
    ) {
        boolean anyReadOnlyOperation = operations.stream()
                .anyMatch(GenerationScenarioDecision::isReadOnlyOperation);
        boolean allOperationsReadOnly = operations.stream()
                .allMatch(GenerationScenarioDecision::isReadOnlyOperation);
        boolean onlyReadOnlyMode = modes.size() == 1 && modes.contains(GenerationMode.READ_ONLY);
        if (mutability == GenerationMutability.READ_ONLY
                && (!allOperationsReadOnly || !onlyReadOnlyMode)) {
            throw new IllegalArgumentException("只读能力只能声明只读操作与 READ_ONLY 模式");
        }
        if (mutability == GenerationMutability.WRITE
                && (anyReadOnlyOperation || modes.contains(GenerationMode.READ_ONLY))) {
            throw new IllegalArgumentException("写能力不得声明只读操作或 READ_ONLY 模式");
        }
    }

    private static String requireRoute(String route) {
        if (route == null || route.isBlank()) {
            throw new IllegalArgumentException("能力路由不能为空");
        }
        return route.trim();
    }

    private static <E extends Enum<E>> Set<E> immutableEnumSet(
            Set<E> source,
            String message
    ) {
        if (source == null || source.isEmpty()) {
            throw new IllegalArgumentException(message);
        }
        return Collections.unmodifiableSet(EnumSet.copyOf(source));
    }
}
