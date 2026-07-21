package com.rush.rushaicodemother.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/** Route-aware credit reservation policy used before durable generation submission. */
@Data
@Component
@Validated
@ConfigurationProperties(prefix = "app.user-credit.reservation")
public class GenerationCreditReservationProperties {

    private String policyVersion = "route-token-budget-v1";

    @Min(1)
    private long createEstimatedTokens = 150_000L;

    @Min(1)
    private long lightEditEstimatedTokens = 80_000L;

    @Min(1)
    private long agentEditEstimatedTokens = 300_000L;

    @Min(1)
    private long heavyExpertEstimatedTokens = 600_000L;

    @Min(1)
    private int htmlMultiplierPercent = 75;

    @Min(1)
    private int multiFileMultiplierPercent = 100;

    @Min(1)
    private int vueProjectMultiplierPercent = 120;

    @Min(1)
    private int backendProjectMultiplierPercent = 150;

    @Min(1)
    private int fullStackProjectMultiplierPercent = 175;

    @AssertTrue(message = "generation credit reservation policy version must not be blank")
    public boolean isPolicyVersionValid() {
        return policyVersion != null && !policyVersion.isBlank() && policyVersion.length() <= 64;
    }
}
