package com.rush.rushaicodemother.orchestration.pipeline;

import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.decision.GenerationMutability;
import com.rush.rushaicodemother.orchestration.decision.GenerationScenarioDecision;
import com.rush.rushaicodemother.orchestration.intent.IntentOperationType;
import com.rush.rushaicodemother.orchestration.router.GenerationMode;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 从实际 pipeline bean 构建并校验静态能力矩阵。
 *
 * <p>同一场景 key 与模式只能有一个执行所有者；重复声明在 Spring 启动期失败，
 * 缺失声明在任务积分预留和持久化前失败。</p>
 */
@Component
public class GenerationPipelineCapabilityRegistry {

    private final Map<GenerationPipelineCapabilityKey,
            Map<GenerationMode, GenerationPipelineCapability>> capabilities;
    private final Map<GenerationPipelineCapabilityKey, Set<GenerationMode>> capabilityMatrix;

    public GenerationPipelineCapabilityRegistry(List<GenerationPipeline> pipelines) {
        if (pipelines == null || pipelines.isEmpty()) {
            throw new IllegalStateException("至少需要注册一条生成管线能力");
        }
        this.capabilities = buildCapabilities(List.copyOf(pipelines));
        this.capabilityMatrix = exportMatrix(capabilities);
    }

    public GenerationPipelineCapability requireCapability(GenerationScenarioDecision decision) {
        Objects.requireNonNull(decision, "场景决策不能为空");
        return requireCapability(
                decision.operation(),
                decision.mutability(),
                decision.targetType(),
                decision.routeDecision().mode());
    }

    /** 按冻结场景维度查询能力；返回值直接派生自实际 pipeline 声明。 */
    public Optional<GenerationPipelineCapability> findCapability(
            IntentOperationType operation,
            GenerationMutability mutability,
            CodeGenTypeEnum targetType,
            GenerationMode mode) {
        GenerationPipelineCapabilityKey key = new GenerationPipelineCapabilityKey(
                operation, mutability, targetType);
        Objects.requireNonNull(mode, "能力模式不能为空");
        return Optional.ofNullable(capabilities.getOrDefault(key, Map.of()).get(mode));
    }

    public GenerationPipelineCapability requireCapability(
            IntentOperationType operation,
            GenerationMutability mutability,
            CodeGenTypeEnum targetType,
            GenerationMode mode) {
        return findCapability(operation, mutability, targetType, mode)
                .orElseThrow(() -> new GenerationPipelineCapabilityException(
                        new GenerationPipelineCapabilityKey(operation, mutability, targetType), mode));
    }

    public boolean supports(IntentOperationType operation,
                            GenerationMutability mutability,
                            CodeGenTypeEnum targetType,
                            GenerationMode mode) {
        return findCapability(operation, mutability, targetType, mode).isPresent();
    }

    public boolean supports(GenerationScenarioDecision decision) {
        try {
            requireCapability(decision);
            return true;
        } catch (GenerationPipelineCapabilityException unsupported) {
            return false;
        }
    }

    /** 返回只读能力矩阵，供诊断、测试和控制面复用。 */
    public Map<GenerationPipelineCapabilityKey, Set<GenerationMode>> matrix() {
        return capabilityMatrix;
    }

    private Map<GenerationPipelineCapabilityKey,
            Map<GenerationMode, GenerationPipelineCapability>> buildCapabilities(
            List<GenerationPipeline> pipelines
    ) {
        Map<String, GenerationPipeline> routeOwners = new LinkedHashMap<>();
        Map<GenerationPipelineCapabilityKey,
                Map<GenerationMode, GenerationPipelineCapability>> matrix = new LinkedHashMap<>();
        for (GenerationPipeline pipeline : pipelines) {
            Objects.requireNonNull(pipeline, "生成管线不能为空");
            GenerationPipelineCapability capability = Objects.requireNonNull(
                    pipeline.capability(), "生成管线能力不能为空: " + pipeline.route());
            if (!Objects.equals(pipeline.route(), capability.route())) {
                throw new IllegalStateException(
                        "生成管线路由与能力声明不一致: " + pipeline.route());
            }
            GenerationPipeline previousRouteOwner = routeOwners.putIfAbsent(
                    capability.route(), pipeline);
            if (previousRouteOwner != null) {
                throw new IllegalStateException("生成管线路由重复: " + capability.route());
            }
            registerCapability(matrix, capability);
        }
        Map<GenerationPipelineCapabilityKey,
                Map<GenerationMode, GenerationPipelineCapability>> immutable = new LinkedHashMap<>();
        matrix.forEach((key, byMode) -> immutable.put(key, Map.copyOf(byMode)));
        return Collections.unmodifiableMap(immutable);
    }

    private void registerCapability(
            Map<GenerationPipelineCapabilityKey,
                    Map<GenerationMode, GenerationPipelineCapability>> matrix,
            GenerationPipelineCapability capability
    ) {
        for (GenerationPipelineCapabilityKey key : capability.keys()) {
            Map<GenerationMode, GenerationPipelineCapability> byMode =
                    matrix.computeIfAbsent(key, ignored -> new LinkedHashMap<>());
            for (GenerationMode mode : capability.modes()) {
                GenerationPipelineCapability previous = byMode.putIfAbsent(mode, capability);
                if (previous != null) {
                    throw new IllegalStateException(
                            "生成管线能力重复: key=" + key + ", mode=" + mode
                                    + ", routes=" + previous.route() + "," + capability.route());
                }
            }
        }
    }

    private Map<GenerationPipelineCapabilityKey, Set<GenerationMode>> exportMatrix(
            Map<GenerationPipelineCapabilityKey,
                    Map<GenerationMode, GenerationPipelineCapability>> source
    ) {
        Map<GenerationPipelineCapabilityKey, Set<GenerationMode>> exported = new LinkedHashMap<>();
        source.forEach((key, byMode) -> exported.put(
                key,
                Collections.unmodifiableSet(new LinkedHashSet<>(byMode.keySet()))));
        return Collections.unmodifiableMap(exported);
    }
}
