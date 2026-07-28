package com.rush.rushaicodemother.service.prompt;

import com.rush.rushaicodemother.ai.prompt.PromptCatalog;
import com.rush.rushaicodemother.ai.prompt.PromptCatalogSnapshot;
import com.rush.rushaicodemother.ai.prompt.release.PromptReleaseCapabilities;
import com.rush.rushaicodemother.ai.prompt.release.PromptReleaseRecord;
import com.rush.rushaicodemother.ai.prompt.release.PromptReleaseRepository;
import com.rush.rushaicodemother.ai.prompt.release.PromptReleaseRuntime;
import com.rush.rushaicodemother.ai.prompt.release.PromptReleaseSpec;
import com.rush.rushaicodemother.ai.prompt.release.PromptReleaseState;
import com.rush.rushaicodemother.orchestration.benchmark.evidence.ReleaseCandidateFingerprint;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/** 应用一个候选变更后，对完整的所需提示包进行指纹识别。 */
@Component
@RequiredArgsConstructor
public class PromptReleaseCandidateFingerprintService {

    private final PromptCatalog promptCatalog;
    private final PromptReleaseRepository repository;
    private final PromptReleaseRuntime runtime;

    /**
 * 返回指纹。
 *
 * @param promptKey 提示词键
 * @param candidate 候选
 * @return 处理后的提示词发布候选指纹文本
 */
    public String fingerprint(String promptKey, PromptReleaseSpec candidate) {
        TreeMap<String, PromptReleaseSpec> desired = desiredReleases(promptKey, candidate);
        PromptReleaseCapabilities capabilities = runtime.capabilities();
        StringBuilder canonical = new StringBuilder("prompt-release-candidate-v1|");
        for (Map.Entry<String, PromptReleaseSpec> entry : desired.entrySet()) {
            String key = entry.getKey();
            PromptReleaseSpec release = entry.getValue();
            Map<String, String> versions = capabilities.contentHashesByPromptAndVersion()
                    .getOrDefault(key, Map.of());
            ReleaseCandidateFingerprint.appendField(canonical, key);
            ReleaseCandidateFingerprint.appendField(canonical, release.stableVersion());
            ReleaseCandidateFingerprint.appendField(
                    canonical, versions.getOrDefault(release.stableVersion(), ""));
            ReleaseCandidateFingerprint.appendField(canonical, release.canaryVersion());
            ReleaseCandidateFingerprint.appendField(
                    canonical, versions.getOrDefault(release.canaryVersion(), ""));
            ReleaseCandidateFingerprint.appendField(
                    canonical, Integer.toString(release.canaryPercentage()));
        }
        return ReleaseCandidateFingerprint.sha256(canonical.toString());
    }

    /**
 * 返回提示词{@code Bundle}指纹。
 *
 * @param promptKey 提示词键
 * @param candidate 候选
 * @return 处理后的提示词发布候选指纹文本
 */
    public String promptBundleFingerprint(String promptKey, PromptReleaseSpec candidate) {
        PromptReleaseState current = repository.loadCurrent();
        Map<String, PromptReleaseRecord> releases = new LinkedHashMap<>(current.releases());
        PromptReleaseRecord existing = releases.get(promptKey);
        releases.put(promptKey, new PromptReleaseRecord(
                promptKey,
                candidate,
                current.revision(),
                existing == null ? 0L : existing.updatedBy(),
                "Benchmark 候选预演",
                existing == null ? Instant.EPOCH : existing.updatedAt()
        ));
        return runtime.preview(new PromptReleaseState(current.revision(), releases)).bundleId();
    }

    private TreeMap<String, PromptReleaseSpec> desiredReleases(
            String promptKey,
            PromptReleaseSpec candidate) {
        TreeMap<String, PromptReleaseSpec> desired = new TreeMap<>();
        PromptCatalogSnapshot active = promptCatalog.snapshot();
        active.releases().forEach((key, release) -> desired.put(key, new PromptReleaseSpec(
                release.stableVersion(), release.canaryVersion(), release.canaryPercentage())));
        for (PromptReleaseRecord release : repository.loadCurrent().releases().values()) {
            desired.put(release.promptKey(), release.release());
        }
        desired.put(promptKey, candidate);
        return desired;
    }
}
