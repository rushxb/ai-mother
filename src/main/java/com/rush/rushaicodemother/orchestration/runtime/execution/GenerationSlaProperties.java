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

/** Route-specific latency and cost envelopes. */
@Data
@Component
@Validated
@ConfigurationProperties(prefix = "app.generation-sla")
public class GenerationSlaProperties {

    private Map<GenerationMode, Profile> profiles = defaultProfiles();

    private Profile saturatedAgentEdit = profile(
            "agent-edit-saturated", Duration.ofMinutes(2), Duration.ofMinutes(6),
            Duration.ofMinutes(2), 2, 24, 1, 1);

    public Profile profile(GenerationMode mode) {
        Profile configured = profiles == null ? null : profiles.get(mode);
        if (configured == null) {
            throw new IllegalStateException("generation SLA profile is missing for mode: " + mode);
        }
        return configured;
    }

    @AssertTrue(message = "generation SLA profiles are invalid")
    public boolean isConfigurationValid() {
        if (profiles == null || profiles.size() != GenerationMode.values().length
                || saturatedAgentEdit == null || !saturatedAgentEdit.valid()) {
            return false;
        }
        for (GenerationMode mode : GenerationMode.values()) {
            Profile profile = profiles.get(mode);
            if (profile == null || !profile.valid()) {
                return false;
            }
        }
        return true;
    }

    private static Map<GenerationMode, Profile> defaultProfiles() {
        EnumMap<GenerationMode, Profile> profiles = new EnumMap<>(GenerationMode.class);
        profiles.put(GenerationMode.CREATE, profile(
                "create-preview-first", Duration.ofSeconds(60), Duration.ofMinutes(10),
                Duration.ofMinutes(2), 2, 40, 2, 1));
        profiles.put(GenerationMode.LIGHT_EDIT, profile(
                "light-edit-fast", Duration.ofSeconds(90), Duration.ofMinutes(4),
                Duration.ofSeconds(90), 2, 12, 1, 1));
        profiles.put(GenerationMode.AGENT_EDIT, profile(
                "agent-edit-balanced", Duration.ofMinutes(3), Duration.ofMinutes(8),
                Duration.ofMinutes(3), 2, 48, 2, 1));
        profiles.put(GenerationMode.HEAVY_EXPERT, profile(
                "heavy-expert-quality", Duration.ofMinutes(5), Duration.ofMinutes(15),
                Duration.ofMinutes(4), 3, 120, 3, 2));
        return profiles;
    }

    private static Profile profile(String name,
                                   Duration firstPreviewTimeout,
                                   Duration totalTimeout,
                                   Duration modelCallTimeout,
                                   int maxModelAttempts,
                                   int maxToolWrites,
                                   int maxBuildExecutions,
                                   int maxRepairRounds) {
        Profile profile = new Profile();
        profile.setName(name);
        profile.setFirstPreviewTimeout(firstPreviewTimeout);
        profile.setTotalTimeout(totalTimeout);
        profile.setModelCallTimeout(modelCallTimeout);
        profile.setMaxModelAttempts(maxModelAttempts);
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
        private int maxModelAttempts;
        private int maxToolWrites;
        private int maxBuildExecutions;
        private int maxRepairRounds;

        boolean valid() {
            return name != null && !name.isBlank()
                    && positive(firstPreviewTimeout)
                    && positive(totalTimeout)
                    && positive(modelCallTimeout)
                    && positive(minimumOperationTimeout)
                    && firstPreviewTimeout.compareTo(totalTimeout) <= 0
                    && modelCallTimeout.compareTo(totalTimeout) <= 0
                    && minimumOperationTimeout.compareTo(modelCallTimeout) < 0
                    && maxModelAttempts > 0
                    && maxToolWrites > 0
                    && maxBuildExecutions > 0
                    && maxRepairRounds > 0;
        }

        private boolean positive(Duration value) {
            return value != null && !value.isZero() && !value.isNegative();
        }
    }
}
