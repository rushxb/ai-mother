package com.rush.rushaicodemother.orchestration.runtime.execution;

import com.rush.rushaicodemother.orchestration.router.GenerationMode;
import jakarta.validation.constraints.AssertTrue;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;

/** 按生成路由隔离延迟、模型调用和工具执行预算。 */
@Data
@Component
@Validated
@ConfigurationProperties(prefix = "app.generation-sla")
public class GenerationSlaProperties {

    private static final int MAX_ROOT_MODEL_ATTEMPTS = 10;
    private static final int MAX_MODEL_TURNS = 100;
    private static final int MAX_PROVIDER_FAILOVER_ATTEMPTS = 100;
    private static final int MAX_TOOL_WRITES = 500;
    private static final int MAX_BUILD_EXECUTIONS = 20;
    private static final int MAX_REPAIR_ROUNDS = 10;

    private Map<GenerationMode, Profile> profiles = defaultProfiles();

    private Profile saturatedAgentEdit = profile(
            "agent-edit-saturated", Duration.ofMinutes(2), Duration.ofMinutes(6),
            Duration.ofMinutes(2), Duration.ofSeconds(30), 2, 8, 2, 24, 1, 1);

    public Profile profile(GenerationMode mode) {
        Profile configured = profiles == null ? null : profiles.get(mode);
        if (configured == null) {
            throw new IllegalStateException("缺少生成模式对应的 SLA 配置：" + mode);
        }
        return configured;
    }

    @AssertTrue(message = "生成任务 SLA 配置无效")
    public boolean isConfigurationValid() {
        if (profiles == null || profiles.size() != GenerationMode.values().length
                || saturatedAgentEdit == null || !saturatedAgentEdit.valid()) {
            return false;
        }
        for (GenerationMode mode : GenerationMode.values()) {
            Profile profile = profiles.get(mode);
            if (profile == null || !profile.valid()
                    || !GenerationRootModelBudgetTopology.supports(
                    mode, profile.getMaxRootModelAttempts(), profile.getMaxRepairRounds())) {
                return false;
            }
        }
        return GenerationRootModelBudgetTopology.supports(
                GenerationMode.AGENT_EDIT,
                saturatedAgentEdit.getMaxRootModelAttempts(),
                saturatedAgentEdit.getMaxRepairRounds());
    }

    private static Map<GenerationMode, Profile> defaultProfiles() {
        EnumMap<GenerationMode, Profile> profiles = new EnumMap<>(GenerationMode.class);
        profiles.put(GenerationMode.CREATE, profile(
                "create-preview-first", Duration.ofSeconds(60), Duration.ofMinutes(10),
                Duration.ofMinutes(2), Duration.ofSeconds(45), 4, 18, 4, 40, 2, 1));
        profiles.put(GenerationMode.LIGHT_EDIT, profile(
                "light-edit-fast", Duration.ofSeconds(90), Duration.ofMinutes(4),
                Duration.ofSeconds(90), Duration.ofSeconds(30), 2, 4, 2, 12, 1, 1));
        profiles.put(GenerationMode.AGENT_EDIT, profile(
                "agent-edit-balanced", Duration.ofMinutes(3), Duration.ofMinutes(8),
                Duration.ofMinutes(3), Duration.ofSeconds(45), 2, 12, 4, 48, 2, 1));
        profiles.put(GenerationMode.HEAVY_EXPERT, profile(
                "heavy-expert-quality", Duration.ofMinutes(5), Duration.ofMinutes(15),
                Duration.ofMinutes(4), Duration.ofMinutes(1), 4, 24, 6, 120, 3, 2));
        return profiles;
    }

    private static Profile profile(String name,
                                   Duration firstPreviewTimeout,
                                   Duration totalTimeout,
                                   Duration modelCallTimeout,
                                   Duration firstPreviewCompletionReserve,
                                   int maxRootModelAttempts,
                                   int maxModelTurns,
                                   int maxProviderFailoverAttempts,
                                   int maxToolWrites,
                                   int maxBuildExecutions,
                                   int maxRepairRounds) {
        Profile profile = new Profile();
        profile.setName(name);
        profile.setFirstPreviewTimeout(firstPreviewTimeout);
        profile.setTotalTimeout(totalTimeout);
        profile.setModelCallTimeout(modelCallTimeout);
        profile.setFirstPreviewCompletionReserve(firstPreviewCompletionReserve);
        profile.setMaxRootModelAttempts(maxRootModelAttempts);
        profile.setMaxModelTurns(maxModelTurns);
        profile.setMaxProviderFailoverAttempts(maxProviderFailoverAttempts);
        profile.setMaxToolWrites(maxToolWrites);
        profile.setMaxBuildExecutions(maxBuildExecutions);
        profile.setMaxRepairRounds(maxRepairRounds);
        return profile;
    }

    @Data
    public static class Profile {

        private String name;
        private Duration firstPreviewTimeout;
        private Duration totalTimeout;
        private Duration modelCallTimeout;
        private Duration minimumOperationTimeout = Duration.ofMillis(500);
        private Duration firstPreviewCompletionReserve = Duration.ofSeconds(15);
        private int maxRootModelAttempts;
        private int maxModelTurns;
        private int maxProviderFailoverAttempts;
        private int maxToolWrites;
        private int maxBuildExecutions;
        private int maxRepairRounds;

        boolean valid() {
            return name != null && !name.isBlank()
                    && positive(firstPreviewTimeout)
                    && positive(totalTimeout)
                    && positive(modelCallTimeout)
                    && positive(minimumOperationTimeout)
                    && positive(firstPreviewCompletionReserve)
                    && firstPreviewTimeout.compareTo(totalTimeout) <= 0
                    && modelCallTimeout.compareTo(totalTimeout) <= 0
                    && minimumOperationTimeout.compareTo(modelCallTimeout) < 0
                    && minimumOperationTimeout.compareTo(firstPreviewTimeout) < 0
                    && firstPreviewCompletionReserve.compareTo(
                    firstPreviewTimeout.minus(minimumOperationTimeout)) <= 0
                    && within(maxRootModelAttempts, MAX_ROOT_MODEL_ATTEMPTS)
                    && within(maxModelTurns, MAX_MODEL_TURNS)
                    && within(maxProviderFailoverAttempts, MAX_PROVIDER_FAILOVER_ATTEMPTS)
                    && within(maxToolWrites, MAX_TOOL_WRITES)
                    && within(maxBuildExecutions, MAX_BUILD_EXECUTIONS)
                    && within(maxRepairRounds, MAX_REPAIR_ROUNDS);
        }

        private boolean positive(Duration value) {
            return value != null && !value.isZero() && !value.isNegative();
        }

        private boolean within(int value, int maximum) {
            return value > 0 && value <= maximum;
        }
    }
}
