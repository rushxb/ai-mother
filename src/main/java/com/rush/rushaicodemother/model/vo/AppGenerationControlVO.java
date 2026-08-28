package com.rush.rushaicodemother.model.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.rush.rushaicodemother.orchestration.governance.app.AppGenerationControlPolicy;

import java.time.Instant;

/** 版本化的应用生成控制公共响应。 */
public record AppGenerationControlVO(
        Long appId,
        long version,
        boolean generationPaused,
        boolean emergencyStopped,
        int maxConcurrentTasks,
        String modelPolicy,
        String dependencyMutationPolicy,
        String dependencyNetworkPolicy,
        String dangerousToolPolicy,
        Long monthlyCreditLimit,
        boolean inheritsTenantBudget,
        Long updatedBy,
        @JsonFormat(shape = JsonFormat.Shape.STRING) Instant updatedAt,
        int contractVersion
) {

    public static final int CURRENT_CONTRACT_VERSION = 1;

    public static AppGenerationControlVO from(AppGenerationControlPolicy policy) {
        return new AppGenerationControlVO(
                policy.appId(),
                policy.version(),
                policy.generationPaused(),
                policy.emergencyStopped(),
                policy.maxConcurrentTasks(),
                policy.modelPolicy().name(),
                policy.dependencyMutationPolicy().name(),
                policy.dependencyNetworkPolicy().name(),
                policy.dangerousToolPolicy().name(),
                policy.monthlyCreditLimit(),
                !policy.hasMonthlyCreditLimit(),
                policy.updatedBy(),
                policy.updatedAt(),
                CURRENT_CONTRACT_VERSION
        );
    }
}
