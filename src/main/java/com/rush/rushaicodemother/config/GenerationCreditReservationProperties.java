package com.rush.rushaicodemother.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/** 在持久生成提交之前使用的路由感知信用预留固定策略。 */
@Data
@Component
@Validated
public class GenerationCreditReservationProperties {

    public static final String POLICY_VERSION = "route-token-budget-v1";
    public static final long CREATE_ESTIMATED_TOKENS = 150_000L;
    public static final long LIGHT_EDIT_ESTIMATED_TOKENS = 80_000L;
    public static final long AGENT_EDIT_ESTIMATED_TOKENS = 300_000L;
    public static final long HEAVY_EXPERT_ESTIMATED_TOKENS = 600_000L;
    public static final int HTML_MULTIPLIER_PERCENT = 75;
    public static final int MULTI_FILE_MULTIPLIER_PERCENT = 100;
    public static final int VUE_PROJECT_MULTIPLIER_PERCENT = 120;
    public static final int BACKEND_PROJECT_MULTIPLIER_PERCENT = 150;
    public static final int FULL_STACK_PROJECT_MULTIPLIER_PERCENT = 175;

    private String policyVersion = POLICY_VERSION;

    @Min(1)
    private long createEstimatedTokens = CREATE_ESTIMATED_TOKENS;

    @Min(1)
    private long lightEditEstimatedTokens = LIGHT_EDIT_ESTIMATED_TOKENS;

    @Min(1)
    private long agentEditEstimatedTokens = AGENT_EDIT_ESTIMATED_TOKENS;

    @Min(1)
    private long heavyExpertEstimatedTokens = HEAVY_EXPERT_ESTIMATED_TOKENS;

    @Min(1)
    private int htmlMultiplierPercent = HTML_MULTIPLIER_PERCENT;

    @Min(1)
    private int multiFileMultiplierPercent = MULTI_FILE_MULTIPLIER_PERCENT;

    @Min(1)
    private int vueProjectMultiplierPercent = VUE_PROJECT_MULTIPLIER_PERCENT;

    @Min(1)
    private int backendProjectMultiplierPercent = BACKEND_PROJECT_MULTIPLIER_PERCENT;

    @Min(1)
    private int fullStackProjectMultiplierPercent = FULL_STACK_PROJECT_MULTIPLIER_PERCENT;

    @AssertTrue(message = "generation credit reservation policy version must not be blank")
    public boolean isPolicyVersionValid() {
        return policyVersion != null && !policyVersion.isBlank() && policyVersion.length() <= 64;
    }
}
