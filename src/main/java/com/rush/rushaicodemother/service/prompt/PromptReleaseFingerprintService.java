package com.rush.rushaicodemother.service.prompt;

import com.rush.rushaicodemother.ai.prompt.PromptCatalog;
import com.rush.rushaicodemother.ai.prompt.release.PromptReleaseRepository;
import com.rush.rushaicodemother.ai.prompt.release.PromptReleaseRuntime;
import com.rush.rushaicodemother.orchestration.benchmark.evidence.GenerationBenchmarkPromptFingerprintProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 计算当前运行态与持久化目标态的 Prompt 版本包指纹。 */
@Component
@RequiredArgsConstructor
public class PromptReleaseFingerprintService
        implements GenerationBenchmarkPromptFingerprintProvider {

    private final PromptCatalog promptCatalog;
    private final PromptReleaseRepository repository;
    private final PromptReleaseRuntime runtime;

    @Override
    public String currentRuntimeFingerprint() {
        return promptCatalog.bundleId();
    }

    /**
 * 返回当前持久指纹。
 *
 * @return 处理后的提示词发布指纹文本
 */
    @Override
    public String currentDurableFingerprint() {
        return runtime.preview(repository.loadCurrent()).bundleId();
    }
}
