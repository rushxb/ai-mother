package com.rush.rushaicodemother.orchestration.release;

import com.rush.rushaicodemother.orchestration.benchmark.evidence.GenerationBenchmarkModelFingerprintProvider;
import com.rush.rushaicodemother.orchestration.benchmark.evidence.GenerationBenchmarkPromptFingerprintProvider;
import com.rush.rushaicodemother.orchestration.benchmark.evidence.GenerationGitBuildMetadataProvider;
import com.rush.rushaicodemother.orchestration.benchmark.evidence.GenerationRuntimeConfigurationFingerprintService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * 把构建、运行策略、Prompt、模型与决策规则收敛为唯一生成发布身份。
 *
 * <p>开发制品允许携带 dirty 标记以保持本地链路可用；生产启动门禁仍由发布来源校验器
 * 强制要求干净构建。dirty 状态会参与指纹，不能伪装成正式发布。</p>
 */
@Component
@RequiredArgsConstructor
public class GenerationExecutionReleaseIdentityProvider {

    private final GenerationGitBuildMetadataProvider buildMetadataProvider;
    private final GenerationRuntimeConfigurationFingerprintService runtimeFingerprintService;
    private final GenerationBenchmarkPromptFingerprintProvider promptFingerprintProvider;
    private final GenerationBenchmarkModelFingerprintProvider modelFingerprintProvider;

    /** 返回当前进程针对指定决策规则的真实发布身份。 */
    public GenerationExecutionReleaseIdentity current(String decisionRuleVersion) {
        GenerationGitBuildMetadataProvider.BuildMetadata build = Objects.requireNonNull(
                buildMetadataProvider.current(), "Git 构建元数据不能为空");
        return new GenerationExecutionReleaseIdentity(
                build.commit(),
                build.dirty(),
                runtimeFingerprintService.currentFingerprint(),
                promptFingerprintProvider.currentRuntimeFingerprint(),
                modelFingerprintProvider.currentFingerprint(),
                decisionRuleVersion);
    }
}
