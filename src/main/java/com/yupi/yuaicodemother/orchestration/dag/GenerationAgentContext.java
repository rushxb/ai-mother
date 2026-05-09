package com.yupi.yuaicodemother.orchestration.dag;

import com.yupi.yuaicodemother.model.enums.CodeGenTypeEnum;
import com.yupi.yuaicodemother.orchestration.GenerationOrchestrationRequest;
import com.yupi.yuaicodemother.orchestration.artifact.GenerationArtifact;
import com.yupi.yuaicodemother.orchestration.artifact.QualityGateResult;
import lombok.Getter;
import lombok.Setter;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * DAG 执行上下文。
 */
@Getter
public class GenerationAgentContext {

    private final GenerationOrchestrationRequest request;
    private final GenerationOrchestrationTask task;
    private final Map<String, GenerationArtifact> artifacts = new LinkedHashMap<>();
    private final Map<String, Long> timings = new LinkedHashMap<>();

    @Setter
    private CodeGenTypeEnum targetType;

    @Setter
    private boolean upgradeRequired;

    @Setter
    private QualityGateResult qualityGateResult;

    public GenerationAgentContext(GenerationOrchestrationRequest request, GenerationOrchestrationTask task) {
        this.request = request;
        this.task = task;
        this.targetType = request.currentType();
    }

    public synchronized void putArtifacts(Collection<GenerationArtifact> newArtifacts) {
        if (newArtifacts == null) {
            return;
        }
        for (GenerationArtifact artifact : newArtifacts) {
            artifacts.put(artifact.key(), artifact);
        }
    }

    public synchronized Optional<GenerationArtifact> getArtifact(String key) {
        return Optional.ofNullable(artifacts.get(key));
    }

    public synchronized Object getArtifactValue(String artifactKey, String payloadKey) {
        GenerationArtifact artifact = artifacts.get(artifactKey);
        if (artifact == null || artifact.payload() == null) {
            return null;
        }
        return artifact.payload().get(payloadKey);
    }

    public synchronized void recordTiming(String nodeKey, long durationMs) {
        timings.put(nodeKey, durationMs);
    }
}
