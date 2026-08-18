package com.rush.rushaicodemother.orchestration.heavy;

import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** 工程类型能力注册表的统一启动期契约。 */
final class GenerationProjectAdapterRegistry {

    private GenerationProjectAdapterRegistry() {
    }

    static <A extends GenerationProjectTypeAdapter> Map<CodeGenTypeEnum, A> register(
            List<A> adapters,
            String capabilityName
    ) {
        if (adapters == null || adapters.isEmpty()) {
            throw new IllegalStateException("至少需要注册一个工程" + capabilityName + "适配器");
        }
        EnumMap<CodeGenTypeEnum, A> registered = new EnumMap<>(CodeGenTypeEnum.class);
        for (A adapter : adapters) {
            if (adapter == null || adapter.codeGenType() == null) {
                throw new IllegalStateException("工程" + capabilityName + "适配器必须声明工程类型");
            }
            A previous = registered.putIfAbsent(adapter.codeGenType(), adapter);
            if (previous != null) {
                throw new IllegalStateException(
                        "工程类型存在重复" + capabilityName + "适配器: "
                                + adapter.codeGenType().getValue());
            }
        }
        return Map.copyOf(registered);
    }
}
