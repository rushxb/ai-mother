package com.rush.rushaicodemother.orchestration.benchmark.evidence;

import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 从实际运行配置和 Maven 构建元数据构造发布来源清单。 */
@Component
@RequiredArgsConstructor
public class BuildGenerationReleaseProvenanceProvider
        implements GenerationReleaseProvenanceProvider {

    private final GenerationGitBuildMetadataProvider gitBuildMetadataProvider;
    private final GenerationRuntimeConfigurationFingerprintService runtimeFingerprintService;

    /**
 * 返回当前。
 *
 * @return 构建生成发布来源信息提供方
 */
    @Override
    public GenerationReleaseProvenanceManifest current() {
        GenerationGitBuildMetadataProvider.BuildMetadata build =
                gitBuildMetadataProvider.current();
        if (build.dirty()) {
            throw new BusinessException(
                    ErrorCode.OPERATION_ERROR,
                    "发布制品来自未提交的工作区，禁止使用 Benchmark 发布证据"
            );
        }
        return new GenerationReleaseProvenanceManifest(
                runtimeFingerprintService.currentFingerprint(),
                build.commit()
        );
    }
}
