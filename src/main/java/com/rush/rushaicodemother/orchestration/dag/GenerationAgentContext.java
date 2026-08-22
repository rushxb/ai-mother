package com.rush.rushaicodemother.orchestration.dag;

import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.GenerationOrchestrationRequest;
import com.rush.rushaicodemother.orchestration.artifact.ContextSummaryArtifact;
import com.rush.rushaicodemother.orchestration.artifact.GenerationArtifact;
import com.rush.rushaicodemother.orchestration.artifact.GenerationRequirementsArtifact;
import com.rush.rushaicodemother.orchestration.artifact.QualityGateArtifact;
import com.rush.rushaicodemother.orchestration.artifact.QualityGateResult;
import com.rush.rushaicodemother.orchestration.index.WorkspaceSemanticIndex;
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

    @Setter
    private WorkspaceSemanticIndex workspaceIndexSnapshot;

    public GenerationAgentContext(GenerationOrchestrationRequest request,
                                  GenerationOrchestrationTask task,
                                  boolean heavyPath) {
        this.request = request;
        this.task = task;
        this.heavyPath = heavyPath;
        this.targetType = request.currentType();
        restoreCheckpointState();
    }

    public String getOrchestrationMode() {
        return heavyPath ? "heavy" : "light";
    }

    /**
 * 处理{@code put}{@code Artifacts}。
 *
 * @param newArtifacts 待处理的 {@code newArtifacts} 集合
 */
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

    /**
 * 获取并返回制品值。
 *
 * @param artifactKey 制品键
 * @param payloadKey 载荷键
 * @return 生成智能体上下文
 */
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

    /** 处理恢复检查点状态。 */
    private void restoreCheckpointState() {
        if (task.getArtifacts() != null) {
            artifacts.putAll(task.getArtifacts());
        }
        if (task.getTimings() != null) {
            timings.putAll(task.getTimings());
        }
        GenerationArtifact requirements = artifacts.get(GenerationRequirementsArtifact.KEY);
        if (requirements != null) {
            GenerationRequirementsArtifact restored = GenerationRequirementsArtifact.fromArtifact(
                    requirements, targetType);
            targetType = restored.targetType();
            upgradeRequired = restored.upgradeRequired();
        }
        GenerationArtifact contextSummary = artifacts.get(ContextSummaryArtifact.KEY);
        if (contextSummary != null) {
            // 恢复时立即验证，避免损坏字段到 Code 阶段才以强转异常暴露。
            ContextSummaryArtifact.fromArtifact(contextSummary);
        }
        GenerationArtifact qualityGate = artifacts.get(QualityGateArtifact.KEY);
        if (qualityGate != null) {
            QualityGateArtifact.ReviewSubject reviewSubject = QualityGateArtifact.reviewSubject(
                    targetType,
                    artifacts
            );
            qualityGateResult = QualityGateArtifact.fromArtifact(
                    qualityGate,
                    reviewSubject
            ).result();
        }
    }
}
