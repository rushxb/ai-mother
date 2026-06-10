package com.rush.rushaicodemother.orchestration.dag;

import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.GenerationOrchestrationRequest;
import com.rush.rushaicodemother.orchestration.artifact.GenerationArtifact;
import com.rush.rushaicodemother.orchestration.artifact.QualityGateResult;
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
    private final boolean heavyPath;
    private final Map<String, GenerationArtifact> artifacts = new LinkedHashMap<>();
    private final Map<String, Long> timings = new LinkedHashMap<>();

    @Setter
    private CodeGenTypeEnum targetType;

    @Setter
    private boolean upgradeRequired;

    @Setter
    private QualityGateResult qualityGateResult;

    public GenerationAgentContext(GenerationOrchestrationRequest request,
                                  GenerationOrchestrationTask task,
                                  boolean heavyPath) {
        this.request = request;
        this.task = task;
        this.heavyPath = heavyPath;
        this.targetType = request.currentType();
    }

    public String getOrchestrationMode() {
        return heavyPath ? "heavy" : "light";
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
