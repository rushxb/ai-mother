package com.rush.rushaicodemother.orchestration.benchmark.evidence;

import com.rush.rushaicodemother.ai.prompt.PromptCatalog;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkReport;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 确保签名的基准元数据描述了报告和正在更改的发布目标。 */
@Component
@RequiredArgsConstructor
public class GenerationBenchmarkEvidenceProvenanceValidator {

    private final PromptCatalog promptCatalog;
    private final GenerationReleaseProvenanceProvider releaseProvenanceProvider;
    private final GenerationBenchmarkModelFingerprintProvider modelFingerprintProvider;

    public void validate(GenerationBenchmarkEvidencePayload payload,
                         GenerationBenchmarkReport report) {
        // 先处理前置条件和快速返回分支，避免无效输入进入核心流程。
        if (payload == null || report == null
                || !payload.promptBundleFingerprint().equals(report.promptBundleId())) {
            throw new BusinessException(
                    ErrorCode.OPERATION_ERROR,
                    "Benchmark 证据的 Prompt 版本包与报告不一致"
            );
        }
        if (!payload.modelFingerprint().equals(report.modelFingerprint())) {
            throw new BusinessException(
                    ErrorCode.OPERATION_ERROR,
                    "Benchmark 证据的模型池与报告不一致"
            );
        }

        GenerationReleaseProvenanceManifest manifest = releaseProvenanceProvider.current();
        if (!payload.runtimeConfigFingerprint().equals(manifest.runtimeConfigFingerprint())
                || !payload.gitCommit().equals(manifest.gitCommit())) {
            throw new BusinessException(
                    ErrorCode.OPERATION_ERROR,
                    "Benchmark 证据与当前发布制品或运行配置不一致"
            );
        }

        if (payload.subjectType() == GenerationBenchmarkEvidenceSubject.AI_MODEL_ENABLE) {
            if (!payload.promptBundleFingerprint().equals(promptCatalog.bundleId())) {
                throw new BusinessException(
                        ErrorCode.OPERATION_ERROR,
                        "Benchmark 模型候选证据使用的 Prompt 版本包已过期"
                );
            }
            return;
        }

        if (!payload.modelFingerprint().equals(modelFingerprintProvider.currentFingerprint())) {
            throw new BusinessException(
                    ErrorCode.OPERATION_ERROR,
                    "Benchmark 证据使用的模型池已发生变化"
            );
        }
    }
}
