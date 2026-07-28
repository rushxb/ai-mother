package com.rush.rushaicodemother.orchestration.benchmark.worker;

import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.orchestration.benchmark.evidence.GenerationBenchmarkEvidenceCandidate;
import com.rush.rushaicodemother.service.aimodel.AiModelConfiguration;
import com.rush.rushaicodemother.service.aimodel.AiModelConfigurationPolicy;
import com.rush.rushaicodemother.service.aimodel.AiModelEnabledConfigurationSnapshot;
import com.rush.rushaicodemother.service.aimodel.AiModelPersistenceService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** 首次读取时冻结 Worker 实际使用的完整模型池。 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        prefix = "app.generation-benchmark.worker",
        name = "enabled",
        havingValue = "true")
public class GenerationBenchmarkAiModelConfigurationSnapshot
        implements AiModelEnabledConfigurationSnapshot {

    private static final Comparator<AiModelConfiguration> MODEL_ORDER = Comparator
            .comparing(
                    AiModelConfiguration::getSortOrder,
                    Comparator.nullsLast(Integer::compareTo))
            .thenComparing(
                    AiModelConfiguration::getId,
                    Comparator.nullsLast(Long::compareTo));

    private final GenerationBenchmarkWorkerCandidateProvider candidateProvider;
    private final AiModelPersistenceService persistenceService;
    private final AiModelConfigurationPolicy configurationPolicy;
    private volatile List<AiModelConfiguration> frozen;

    /**
 * 启用{@code d}模型。
 *
 * @return {@code d}模型集合
 */
    @Override
    public List<AiModelConfiguration> enabledModels() {
        List<AiModelConfiguration> current = frozen;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (frozen == null) {
                frozen = loadSnapshot();
            }
            return frozen;
        }
    }

    /** 加载快照。 */
    private List<AiModelConfiguration> loadSnapshot() {
        List<AiModelConfiguration> models = new ArrayList<>(
                persistenceService.findEnabled(null));
        GenerationBenchmarkEvidenceCandidate candidate = candidateProvider.candidate();
        AiModelConfiguration enabledCandidate = null;
        if (candidate instanceof GenerationBenchmarkEvidenceCandidate.AiModelEnable modelCandidate) {
            AiModelConfiguration configuration = persistenceService.findActiveById(
                    modelCandidate.modelId());
            if (configuration == null || configuration.enabled()) {
                throw new BusinessException(
                        ErrorCode.OPERATION_ERROR,
                        "Benchmark 模型候选必须是当前存在且处于停用状态的模型"
                );
            }
            enabledCandidate = configurationPolicy.normalizeAndValidate(
                    configuration.toBuilder().isEnabled(1).build());
            models.add(enabledCandidate);
        }
        models.sort(MODEL_ORDER);
        prioritizeCandidateWithinType(models, enabledCandidate);
        return List.copyOf(models);
    }

    /** 处理{@code prioritize}候选{@code Within}类型。 */
    private void prioritizeCandidateWithinType(List<AiModelConfiguration> models,
                                               AiModelConfiguration candidate) {
        if (candidate == null) {
            return;
        }
        models.remove(candidate);
        int insertionIndex = 0;
        while (insertionIndex < models.size()
                && !candidate.getModelType().equals(models.get(insertionIndex).getModelType())) {
            insertionIndex++;
        }
        models.add(insertionIndex, candidate);
    }
}
