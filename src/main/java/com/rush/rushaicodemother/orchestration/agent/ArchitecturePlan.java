package com.rush.rushaicodemother.orchestration.agent;

import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/** Architect 与 Code 之间的最小强类型计划契约。 */
public record ArchitecturePlan(
        List<String> modules,
        List<String> constraints,
        CodeGenTypeEnum targetType,
        boolean parallelizable
) {

    public ArchitecturePlan {
        modules = modules == null ? List.of() : modules.stream().filter(value -> value != null && !value.isBlank()).toList();
        constraints = constraints == null ? List.of() : constraints.stream().filter(value -> value != null && !value.isBlank()).toList();
        if (modules.isEmpty()) {
            throw new IllegalArgumentException("架构计划至少需要一个模块");
        }
        if (targetType == null) {
            throw new IllegalArgumentException("架构计划目标类型不能为空");
        }
    }

    public Map<String, Object> toPayload() {
        return Map.of(
                "modules", modules,
                "constraints", constraints,
                "targetType", targetType.getValue(),
                "parallelizable", parallelizable
        );
    }

    public static ArchitecturePlan fromPayload(Map<String, Object> payload) {
        return fromPayload(payload, null);
    }

    /** 兼容尚未携带目标类型的旧检查点，恢复后仍立即转成强类型对象。 */
    public static ArchitecturePlan fromPayload(Map<String, Object> payload,
                                               CodeGenTypeEnum fallbackTargetType) {
        if (payload == null) {
            throw new IllegalArgumentException("架构计划载荷不能为空");
        }
        List<String> modules = strings(payload.get("modules"));
        List<String> constraints = strings(payload.get("constraints"));
        CodeGenTypeEnum targetType = CodeGenTypeEnum.getEnumByValue(String.valueOf(payload.get("targetType")));
        if (targetType == null) {
            targetType = fallbackTargetType;
        }
        boolean parallelizable = payload.containsKey("parallelizable")
                ? Boolean.parseBoolean(String.valueOf(payload.get("parallelizable")))
                : modules.size() > 1;
        return new ArchitecturePlan(modules, constraints, targetType, parallelizable);
    }

    private static List<String> strings(Object value) {
        if (!(value instanceof Collection<?> collection)) {
            return List.of();
        }
        return collection.stream().filter(item -> item != null).map(String::valueOf).toList();
    }
}
