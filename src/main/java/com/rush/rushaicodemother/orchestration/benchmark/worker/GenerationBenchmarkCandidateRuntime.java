package com.rush.rushaicodemother.orchestration.benchmark.worker;

import com.rush.rushaicodemother.ai.prompt.PromptCatalog;
import com.rush.rushaicodemother.ai.prompt.PromptCatalogSnapshot;
import com.rush.rushaicodemother.ai.prompt.release.PromptReleaseRecord;
import com.rush.rushaicodemother.ai.prompt.release.PromptReleaseRepository;
import com.rush.rushaicodemother.ai.prompt.release.PromptReleaseRuntime;
import com.rush.rushaicodemother.ai.prompt.release.PromptReleaseState;
import com.rush.rushaicodemother.orchestration.benchmark.evidence.GenerationBenchmarkEvidenceCandidate;
import com.rush.rushaicodemother.service.aimodel.AiModelEnabledConfigurationSnapshot;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** 在独立进程内冻结模型池，并把目标 Prompt 状态装载到运行目录。 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        prefix = "app.generation-benchmark.worker",
        name = "enabled",
        havingValue = "true")
public class GenerationBenchmarkCandidateRuntime {

    private static final long FROZEN_PROMPT_REVISION = Long.MAX_VALUE;

    private final AiModelEnabledConfigurationSnapshot modelSnapshot;
    private final PromptReleaseRepository promptRepository;
    private final PromptReleaseRuntime promptRuntime;
    private final PromptCatalog promptCatalog;

    public void prepare(GenerationBenchmarkEvidenceCandidate candidate) {
        if (candidate == null) {
            throw new IllegalArgumentException("Benchmark Worker 候选不能为空");
        }
        modelSnapshot.enabledModels();
        PromptReleaseState durable = promptRepository.loadCurrent();
        Map<String, PromptReleaseRecord> releases =
                new LinkedHashMap<>(durable.releases());
        if (candidate instanceof GenerationBenchmarkEvidenceCandidate.PromptRelease promptCandidate) {
            releases.put(promptCandidate.promptKey(), new PromptReleaseRecord(
                    promptCandidate.promptKey(),
                    promptCandidate.release(),
                    FROZEN_PROMPT_REVISION,
                    0L,
                    "Benchmark Worker 候选预演",
                    Instant.EPOCH
            ));
        }
        PromptReleaseState frozen = new PromptReleaseState(
                FROZEN_PROMPT_REVISION, releases);
        PromptCatalogSnapshot expected = promptRuntime.preview(frozen);
        promptRuntime.activate(frozen);
        if (!expected.equals(promptCatalog.snapshot())) {
            throw new IllegalStateException("Benchmark Worker 无法冻结目标 Prompt 版本包");
        }
    }
}
