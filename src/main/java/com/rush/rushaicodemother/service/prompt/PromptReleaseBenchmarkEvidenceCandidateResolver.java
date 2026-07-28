package com.rush.rushaicodemother.service.prompt;

import com.rush.rushaicodemother.ai.prompt.release.PromptReleaseCapabilities;
import com.rush.rushaicodemother.ai.prompt.release.PromptReleaseSpec;
import com.rush.rushaicodemother.ai.prompt.release.PromptReleaseRuntime;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.orchestration.benchmark.evidence.GenerationBenchmarkEvidenceCandidate;
import com.rush.rushaicodemother.orchestration.benchmark.evidence.GenerationBenchmarkEvidenceCandidateIdentity;
import com.rush.rushaicodemother.orchestration.benchmark.evidence.GenerationBenchmarkEvidenceCandidateResolver;
import com.rush.rushaicodemother.orchestration.benchmark.evidence.GenerationBenchmarkEvidenceSubject;
import com.rush.rushaicodemother.service.aimodel.AiModelFleetFingerprintService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 根据当前 Prompt 发布状态和模型池解析 Prompt 发布候选身份。 */
@Component
@RequiredArgsConstructor
public class PromptReleaseBenchmarkEvidenceCandidateResolver
        implements GenerationBenchmarkEvidenceCandidateResolver {

    private final PromptReleaseCandidateFingerprintService candidateFingerprintService;
    private final PromptReleaseRuntime runtime;
    private final AiModelFleetFingerprintService modelFingerprintService;

    @Override
    public boolean supports(GenerationBenchmarkEvidenceCandidate candidate) {
        return candidate instanceof GenerationBenchmarkEvidenceCandidate.PromptRelease;
    }

    /**
 * 根据当前上下文解析提示词发布基准测试证据候选。
 *
 * @param candidate 候选
 * @return 提示词发布基准测试证据候选
 */
    @Override
    public GenerationBenchmarkEvidenceCandidateIdentity resolve(
            GenerationBenchmarkEvidenceCandidate candidate) {
        if (!(candidate instanceof GenerationBenchmarkEvidenceCandidate.PromptRelease promptCandidate)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "Prompt 候选类型无效");
        }
        String promptKey = promptCandidate.promptKey() == null
                ? "" : promptCandidate.promptKey().trim();
        PromptReleaseSpec release = promptCandidate.release();
        validateCandidate(promptKey, release);
        return new GenerationBenchmarkEvidenceCandidateIdentity(
                GenerationBenchmarkEvidenceSubject.PROMPT_RELEASE,
                promptKey,
                candidateFingerprintService.fingerprint(promptKey, release),
                modelFingerprintService.currentPersistentFingerprint(),
                candidateFingerprintService.promptBundleFingerprint(promptKey, release)
        );
    }

    /** 校验{@code ate}候选是否有效。 */
    private void validateCandidate(String promptKey, PromptReleaseSpec release) {
        if (promptKey.isBlank() || release == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "Prompt 候选不完整");
        }
        PromptReleaseCapabilities capabilities = runtime.capabilities();
        if (!capabilities.supports(promptKey, release.stableVersion())
                || release.canaryPercentage() < 0 || release.canaryPercentage() > 100) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "Prompt 候选版本不存在或灰度比例无效");
        }
        if (release.canaryPercentage() == 0) {
            if (!release.canaryVersion().isBlank()) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "无灰度时不能填写灰度版本");
            }
            return;
        }
        if (!capabilities.supports(promptKey, release.canaryVersion())
                || release.stableVersion().equals(release.canaryVersion())) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "Prompt 灰度候选版本无效");
        }
    }
}
