package com.rush.rushaicodemother.service.prompt.canary;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rush.rushaicodemother.orchestration.benchmark.evidence.ReleaseCandidateFingerprint;
import com.rush.rushaicodemother.orchestration.learning.GenerationStrategyPromotionGate;
import com.rush.rushaicodemother.orchestration.learning.GenerationStrategyPromotionGateResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/** 用统一策略门禁评估并固化 Prompt stable/canary 生产证据。 */
@Service
public class PromptCanaryAssessmentService {

    private static final Set<String> OBSERVATION_VIOLATIONS = Set.of(
            "baseline_task_count_below_minimum",
            "candidate_task_count_below_minimum",
            "baseline_feedback_count_below_minimum",
            "candidate_feedback_count_below_minimum",
            "baseline_validation_observation_incomplete",
            "candidate_validation_observation_incomplete",
            "baseline_repair_observation_incomplete",
            "candidate_repair_observation_incomplete",
            "baseline_first_useful_observation_incomplete",
            "candidate_first_useful_observation_incomplete",
            "baseline_delivery_observation_incomplete",
            "candidate_delivery_observation_incomplete",
            "baseline_provider_cost_observation_incomplete",
            "candidate_provider_cost_observation_incomplete",
            "baseline_credit_cost_observation_incomplete",
            "candidate_credit_cost_observation_incomplete",
            "baseline_capacity_observation_incomplete",
            "candidate_capacity_observation_incomplete"
    );

    private final PromptCanaryObservationRepository observationRepository;
    private final PromptCanaryAssessmentStore assessmentStore;
    private final GenerationStrategyPromotionGate promotionGate;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Supplier<String> assessmentIdSupplier;

    @Autowired
    public PromptCanaryAssessmentService(
            PromptCanaryObservationRepository observationRepository,
            PromptCanaryAssessmentStore assessmentStore,
            GenerationStrategyPromotionGate promotionGate,
            ObjectMapper objectMapper
    ) {
        this(observationRepository, assessmentStore, promotionGate, objectMapper,
                Clock.systemUTC(), () -> UUID.randomUUID().toString());
    }

    PromptCanaryAssessmentService(
            PromptCanaryObservationRepository observationRepository,
            PromptCanaryAssessmentStore assessmentStore,
            GenerationStrategyPromotionGate promotionGate,
            ObjectMapper objectMapper,
            Clock clock,
            Supplier<String> assessmentIdSupplier
    ) {
        this.observationRepository = Objects.requireNonNull(observationRepository);
        this.assessmentStore = Objects.requireNonNull(assessmentStore);
        this.promotionGate = Objects.requireNonNull(promotionGate);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.clock = Objects.requireNonNull(clock);
        this.assessmentIdSupplier = Objects.requireNonNull(assessmentIdSupplier);
    }

    /** 聚合、判定并持久化一次不可变灰度评估。 */
    public PromptCanaryAssessment assessAndPersist(PromptCanaryEvaluationRequest request) {
        Objects.requireNonNull(request, "Prompt 灰度评估请求不能为空");
        PromptCanaryObservation observation = observationRepository.observe(request);
        GenerationStrategyPromotionGateResult gateResult = promotionGate.assess(
                observation.stable(), observation.canary());
        List<String> violations;
        PromptCanaryDecision decision;
        if (observation.invalidAttributionTaskCount() > 0) {
            violations = List.of("prompt_attribution_inconsistent");
            decision = PromptCanaryDecision.INVALID;
        } else {
            violations = gateResult.violations();
            decision = classify(gateResult);
        }
        Instant evaluatedAt = clock.instant();
        AssessmentEvidence evidence = new AssessmentEvidence(
                request, observation, decision, violations, evaluatedAt);
        String evidenceJson = serialize(evidence);
        PromptCanaryAssessment assessment = new PromptCanaryAssessment(
                assessmentIdSupplier.get(), request, decision, violations,
                observation.stable().quality().taskCount(),
                observation.canary().quality().taskCount(),
                observation.ambiguousTaskCount(),
                observation.invalidAttributionTaskCount(),
                evidenceJson,
                ReleaseCandidateFingerprint.sha256(evidenceJson),
                evaluatedAt
        );
        assessmentStore.save(assessment);
        return assessment;
    }

    private PromptCanaryDecision classify(GenerationStrategyPromotionGateResult result) {
        if (result.passed()) {
            return PromptCanaryDecision.PROMOTABLE;
        }
        if (result.violations().stream().anyMatch(OBSERVATION_VIOLATIONS::contains)) {
            return PromptCanaryDecision.OBSERVING;
        }
        if (result.violations().equals(List.of("candidate_has_no_observed_improvement"))) {
            return PromptCanaryDecision.HOLD;
        }
        return PromptCanaryDecision.ROLLBACK_REQUIRED;
    }

    private String serialize(AssessmentEvidence evidence) {
        try {
            return objectMapper.writeValueAsString(evidence);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Prompt 灰度评估证据无法序列化", exception);
        }
    }

    private record AssessmentEvidence(
            PromptCanaryEvaluationRequest request,
            PromptCanaryObservation observation,
            PromptCanaryDecision decision,
            List<String> violations,
            Instant evaluatedAt
    ) {
    }
}
