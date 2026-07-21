package com.rush.rushaicodemother.service.prompt;

import com.rush.rushaicodemother.ai.prompt.PromptCatalog;
import com.rush.rushaicodemother.ai.prompt.PromptCatalogSnapshot;
import com.rush.rushaicodemother.ai.prompt.release.PromptReleaseCapabilities;
import com.rush.rushaicodemother.ai.prompt.release.PromptReleaseRecord;
import com.rush.rushaicodemother.ai.prompt.release.PromptReleaseRepository;
import com.rush.rushaicodemother.ai.prompt.release.PromptReleaseRuntime;
import com.rush.rushaicodemother.ai.prompt.release.PromptReleaseSpec;
import com.rush.rushaicodemother.orchestration.benchmark.evidence.ReleaseCandidateFingerprint;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.TreeMap;

/** Fingerprints the complete desired prompt bundle after applying one candidate mutation. */
@Component
@RequiredArgsConstructor
public class PromptReleaseCandidateFingerprintService {

    private final PromptCatalog promptCatalog;
    private final PromptReleaseRepository repository;
    private final PromptReleaseRuntime runtime;

    public String fingerprint(String promptKey, PromptReleaseSpec candidate) {
        TreeMap<String, PromptReleaseSpec> desired = new TreeMap<>();
        PromptCatalogSnapshot active = promptCatalog.snapshot();
        active.releases().forEach((key, release) -> desired.put(key, new PromptReleaseSpec(
                release.stableVersion(), release.canaryVersion(), release.canaryPercentage())));
        for (PromptReleaseRecord release : repository.loadCurrent().releases().values()) {
            desired.put(release.promptKey(), release.release());
        }
        desired.put(promptKey, candidate);

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
}
