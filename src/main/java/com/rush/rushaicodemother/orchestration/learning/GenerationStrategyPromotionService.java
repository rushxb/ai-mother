package com.rush.rushaicodemother.orchestration.learning;

import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/** 从真实场景归因事实中选择唯一基线与候选，再执行统一晋级门禁。 */
@Service
@RequiredArgsConstructor
public class GenerationStrategyPromotionService {

    private static final int MAX_BUCKETS = 500;

    private final GenerationScenarioAttributionRepository attributionRepository;
    private final GenerationStrategyPromotionGate promotionGate;

    public GenerationStrategyPromotionAssessment assess(GenerationStrategyPromotionQuery query) {
        if (query == null) {
            throw new IllegalArgumentException("策略晋级查询不能为空");
        }
        List<GenerationScenarioBucketSummary> summaries = attributionRepository.summarize(
                query.intentSignature(), query.from(), query.to(), MAX_BUCKETS);
        GenerationScenarioBucketSummary baseline = requireUnique(
                summaries, query.baselineReleaseIdentity(), "基线");
        GenerationScenarioBucketSummary candidate = requireUnique(
                summaries, query.candidateReleaseIdentity(), "候选");
        return promotionGate.assess(baseline, candidate);
    }

    /**
     * P95 不能由多个已聚合桶再次正确合并，因此同一指纹出现多个路由/决策桶时必须失败，
     * 由调用方缩小场景或时间窗口后重新评估。
     */
    private GenerationScenarioBucketSummary requireUnique(
            List<GenerationScenarioBucketSummary> summaries,
            String releaseIdentity,
            String role) {
        List<GenerationScenarioBucketSummary> matches = summaries == null
                ? List.of()
                : summaries.stream()
                .filter(summary -> summary != null
                        && releaseIdentity.equals(summary.identity().releaseIdentity()))
                .toList();
        if (matches.isEmpty()) {
            throw new BusinessException(
                    ErrorCode.NOT_FOUND_ERROR, role + "策略缺少场景归因证据");
        }
        if (matches.size() > 1) {
            throw new BusinessException(
                    ErrorCode.OPERATION_ERROR, role + "策略归因不唯一，拒绝合并 P95 后晋级");
        }
        return matches.getFirst();
    }
}
