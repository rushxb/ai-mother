package com.rush.rushaicodemother.orchestration.benchmark.evidence;

import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import org.springframework.stereotype.Component;

import java.util.List;

/** 为发布候选选择唯一解析策略，并返回候选生效后的完整执行身份。 */
@Component
public class GenerationBenchmarkEvidenceCandidateIdentityResolver {

    private final List<GenerationBenchmarkEvidenceCandidateResolver> resolvers;

    public GenerationBenchmarkEvidenceCandidateIdentityResolver(
            List<GenerationBenchmarkEvidenceCandidateResolver> resolvers) {
        this.resolvers = resolvers == null ? List.of() : List.copyOf(resolvers);
    }

    public GenerationBenchmarkEvidenceCandidateIdentity resolve(
            GenerationBenchmarkEvidenceCandidate candidate) {
        if (candidate == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "Benchmark 候选不能为空");
        }
        List<GenerationBenchmarkEvidenceCandidateResolver> matches = resolvers.stream()
                .filter(resolver -> resolver.supports(candidate))
                .toList();
        if (matches.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "Benchmark 候选类型不受支持");
        }
        if (matches.size() != 1) {
            throw new IllegalStateException("Benchmark 候选解析器配置发生冲突");
        }
        GenerationBenchmarkEvidenceCandidateIdentity identity = matches.getFirst().resolve(candidate);
        if (identity == null || identity.subjectType() == null) {
            throw new IllegalStateException("Benchmark 候选解析结果不完整");
        }
        return identity;
    }
}
