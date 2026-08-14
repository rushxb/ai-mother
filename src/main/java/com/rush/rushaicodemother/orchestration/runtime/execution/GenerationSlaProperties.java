package com.rush.rushaicodemother.orchestration.runtime.execution;

import com.rush.rushaicodemother.orchestration.router.GenerationMode;
import jakarta.validation.constraints.AssertTrue;
import lombok.Data;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;

/** 按生成路由隔离延迟、模型调用和工具执行的固定预算。 */
@Data
@Component
@Validated
public class GenerationSlaProperties {

    /** 各路由共享的最小操作超时。 */
    public static final Duration MINIMUM_OPERATION_TIMEOUT = Duration.ofMillis(500);

    public static final String READ_ONLY_NAME = "read-only-analysis";
    public static final Duration READ_ONLY_FIRST_PREVIEW_TIMEOUT = Duration.ofSeconds(45);
    public static final Duration READ_ONLY_TOTAL_TIMEOUT = Duration.ofMinutes(2);
    public static final Duration READ_ONLY_MODEL_CALL_TIMEOUT = Duration.ofMinutes(1);
    public static final Duration READ_ONLY_FIRST_PREVIEW_COMPLETION_RESERVE = Duration.ofSeconds(10);
    public static final int READ_ONLY_MAX_ROOT_MODEL_ATTEMPTS = 1;
    public static final int READ_ONLY_MAX_MODEL_TURNS = 1;
    public static final int READ_ONLY_MAX_PROVIDER_FAILOVER_ATTEMPTS = 1;
    public static final int READ_ONLY_MAX_TOOL_WRITES = 0;
    public static final int READ_ONLY_MAX_BUILD_EXECUTIONS = 0;
    public static final int READ_ONLY_MAX_REPAIR_ROUNDS = 0;

    public static final String CREATE_NAME = "create-preview-first";
    public static final Duration CREATE_FIRST_PREVIEW_TIMEOUT = Duration.ofSeconds(60);
    public static final Duration CREATE_TOTAL_TIMEOUT = Duration.ofMinutes(10);
    public static final Duration CREATE_MODEL_CALL_TIMEOUT = Duration.ofMinutes(2);
    public static final Duration CREATE_FIRST_PREVIEW_COMPLETION_RESERVE = Duration.ofSeconds(45);
    public static final int CREATE_MAX_ROOT_MODEL_ATTEMPTS = 4;
    public static final int CREATE_MAX_MODEL_TURNS = 18;
    public static final int CREATE_MAX_PROVIDER_FAILOVER_ATTEMPTS = 4;
    public static final int CREATE_MAX_TOOL_WRITES = 40;
    public static final int CREATE_MAX_BUILD_EXECUTIONS = 2;
    public static final int CREATE_MAX_REPAIR_ROUNDS = 1;

    public static final String LIGHT_EDIT_NAME = "light-edit-fast";
    public static final Duration LIGHT_EDIT_FIRST_PREVIEW_TIMEOUT = Duration.ofSeconds(90);
    public static final Duration LIGHT_EDIT_TOTAL_TIMEOUT = Duration.ofMinutes(4);
    public static final Duration LIGHT_EDIT_MODEL_CALL_TIMEOUT = Duration.ofSeconds(90);
    public static final Duration LIGHT_EDIT_FIRST_PREVIEW_COMPLETION_RESERVE = Duration.ofSeconds(30);
    public static final int LIGHT_EDIT_MAX_ROOT_MODEL_ATTEMPTS = 2;
    public static final int LIGHT_EDIT_MAX_MODEL_TURNS = 4;
    public static final int LIGHT_EDIT_MAX_PROVIDER_FAILOVER_ATTEMPTS = 2;
    public static final int LIGHT_EDIT_MAX_TOOL_WRITES = 12;
    public static final int LIGHT_EDIT_MAX_BUILD_EXECUTIONS = 1;
    public static final int LIGHT_EDIT_MAX_REPAIR_ROUNDS = 1;

    public static final String AGENT_EDIT_NAME = "agent-edit-balanced";
    public static final Duration AGENT_EDIT_FIRST_PREVIEW_TIMEOUT = Duration.ofMinutes(3);
    public static final Duration AGENT_EDIT_TOTAL_TIMEOUT = Duration.ofMinutes(8);
    public static final Duration AGENT_EDIT_MODEL_CALL_TIMEOUT = Duration.ofMinutes(3);
    public static final Duration AGENT_EDIT_FIRST_PREVIEW_COMPLETION_RESERVE = Duration.ofSeconds(45);
    public static final int AGENT_EDIT_MAX_ROOT_MODEL_ATTEMPTS = 2;
    public static final int AGENT_EDIT_MAX_MODEL_TURNS = 12;
    public static final int AGENT_EDIT_MAX_PROVIDER_FAILOVER_ATTEMPTS = 4;
    public static final int AGENT_EDIT_MAX_TOOL_WRITES = 48;
    public static final int AGENT_EDIT_MAX_BUILD_EXECUTIONS = 2;
    public static final int AGENT_EDIT_MAX_REPAIR_ROUNDS = 1;

    public static final String HEAVY_EXPERT_NAME = "heavy-expert-quality";
    public static final Duration HEAVY_EXPERT_FIRST_PREVIEW_TIMEOUT = Duration.ofMinutes(5);
    public static final Duration HEAVY_EXPERT_TOTAL_TIMEOUT = Duration.ofMinutes(15);
    public static final Duration HEAVY_EXPERT_MODEL_CALL_TIMEOUT = Duration.ofMinutes(4);
    public static final Duration HEAVY_EXPERT_FIRST_PREVIEW_COMPLETION_RESERVE = Duration.ofMinutes(1);
    public static final int HEAVY_EXPERT_MAX_ROOT_MODEL_ATTEMPTS = 4;
    public static final int HEAVY_EXPERT_MAX_MODEL_TURNS = 24;
    public static final int HEAVY_EXPERT_MAX_PROVIDER_FAILOVER_ATTEMPTS = 6;
    public static final int HEAVY_EXPERT_MAX_TOOL_WRITES = 120;
    public static final int HEAVY_EXPERT_MAX_BUILD_EXECUTIONS = 3;
    public static final int HEAVY_EXPERT_MAX_REPAIR_ROUNDS = 2;

    public static final String SATURATED_NAME = "agent-edit-saturated";
    public static final Duration SATURATED_FIRST_PREVIEW_TIMEOUT = Duration.ofMinutes(2);
    public static final Duration SATURATED_TOTAL_TIMEOUT = Duration.ofMinutes(6);
    public static final Duration SATURATED_MODEL_CALL_TIMEOUT = Duration.ofMinutes(2);
    public static final Duration SATURATED_FIRST_PREVIEW_COMPLETION_RESERVE = Duration.ofSeconds(30);
    public static final int SATURATED_MAX_ROOT_MODEL_ATTEMPTS = 2;
    public static final int SATURATED_MAX_MODEL_TURNS = 8;
    public static final int SATURATED_MAX_PROVIDER_FAILOVER_ATTEMPTS = 2;
    public static final int SATURATED_MAX_TOOL_WRITES = 24;
    public static final int SATURATED_MAX_BUILD_EXECUTIONS = 1;
    public static final int SATURATED_MAX_REPAIR_ROUNDS = 1;

    private static final int MAX_ROOT_MODEL_ATTEMPTS = 10;
    private static final int MAX_MODEL_TURNS = 100;
    private static final int MAX_PROVIDER_FAILOVER_ATTEMPTS = 100;
    private static final int MAX_TOOL_WRITES = 500;
    private static final int MAX_BUILD_EXECUTIONS = 20;
    private static final int MAX_REPAIR_ROUNDS = 10;

    private Map<GenerationMode, Profile> profiles = defaultProfiles();

    private Profile saturatedAgentEdit = defaultSaturatedAgentEdit();

    /**
 * 返回配置档。
 *
 * @param mode 模式
 * @return 生成{@code Sla}
 */
    public Profile profile(GenerationMode mode) {
        Profile configured = profiles == null ? null : profiles.get(mode);
        if (configured == null) {
            throw new IllegalStateException("缺少生成模式对应的 SLA 配置：" + mode);
        }
        return configured;
    }

    /**
 * 校验当前配置项组合是否合法。
 *
 * @return 满足条件时返回 {@code true}，否则返回 {@code false}
 */
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

    /** 返回默认{@code Profiles}。 */
    private static Map<GenerationMode, Profile> defaultProfiles() {
        EnumMap<GenerationMode, Profile> profiles = new EnumMap<>(GenerationMode.class);
        profiles.put(GenerationMode.READ_ONLY, profile(
                READ_ONLY_NAME, READ_ONLY_FIRST_PREVIEW_TIMEOUT, READ_ONLY_TOTAL_TIMEOUT,
                READ_ONLY_MODEL_CALL_TIMEOUT, READ_ONLY_FIRST_PREVIEW_COMPLETION_RESERVE,
                READ_ONLY_MAX_ROOT_MODEL_ATTEMPTS, READ_ONLY_MAX_MODEL_TURNS,
                READ_ONLY_MAX_PROVIDER_FAILOVER_ATTEMPTS, READ_ONLY_MAX_TOOL_WRITES,
                READ_ONLY_MAX_BUILD_EXECUTIONS, READ_ONLY_MAX_REPAIR_ROUNDS));
        profiles.put(GenerationMode.CREATE, profile(
                CREATE_NAME, CREATE_FIRST_PREVIEW_TIMEOUT, CREATE_TOTAL_TIMEOUT,
                CREATE_MODEL_CALL_TIMEOUT, CREATE_FIRST_PREVIEW_COMPLETION_RESERVE,
                CREATE_MAX_ROOT_MODEL_ATTEMPTS, CREATE_MAX_MODEL_TURNS,
                CREATE_MAX_PROVIDER_FAILOVER_ATTEMPTS, CREATE_MAX_TOOL_WRITES,
                CREATE_MAX_BUILD_EXECUTIONS, CREATE_MAX_REPAIR_ROUNDS));
        profiles.put(GenerationMode.LIGHT_EDIT, profile(
                LIGHT_EDIT_NAME, LIGHT_EDIT_FIRST_PREVIEW_TIMEOUT, LIGHT_EDIT_TOTAL_TIMEOUT,
                LIGHT_EDIT_MODEL_CALL_TIMEOUT, LIGHT_EDIT_FIRST_PREVIEW_COMPLETION_RESERVE,
                LIGHT_EDIT_MAX_ROOT_MODEL_ATTEMPTS, LIGHT_EDIT_MAX_MODEL_TURNS,
                LIGHT_EDIT_MAX_PROVIDER_FAILOVER_ATTEMPTS, LIGHT_EDIT_MAX_TOOL_WRITES,
                LIGHT_EDIT_MAX_BUILD_EXECUTIONS, LIGHT_EDIT_MAX_REPAIR_ROUNDS));
        profiles.put(GenerationMode.AGENT_EDIT, profile(
                AGENT_EDIT_NAME, AGENT_EDIT_FIRST_PREVIEW_TIMEOUT, AGENT_EDIT_TOTAL_TIMEOUT,
                AGENT_EDIT_MODEL_CALL_TIMEOUT, AGENT_EDIT_FIRST_PREVIEW_COMPLETION_RESERVE,
                AGENT_EDIT_MAX_ROOT_MODEL_ATTEMPTS, AGENT_EDIT_MAX_MODEL_TURNS,
                AGENT_EDIT_MAX_PROVIDER_FAILOVER_ATTEMPTS, AGENT_EDIT_MAX_TOOL_WRITES,
                AGENT_EDIT_MAX_BUILD_EXECUTIONS, AGENT_EDIT_MAX_REPAIR_ROUNDS));
        profiles.put(GenerationMode.HEAVY_EXPERT, profile(
                HEAVY_EXPERT_NAME, HEAVY_EXPERT_FIRST_PREVIEW_TIMEOUT, HEAVY_EXPERT_TOTAL_TIMEOUT,
                HEAVY_EXPERT_MODEL_CALL_TIMEOUT, HEAVY_EXPERT_FIRST_PREVIEW_COMPLETION_RESERVE,
                HEAVY_EXPERT_MAX_ROOT_MODEL_ATTEMPTS, HEAVY_EXPERT_MAX_MODEL_TURNS,
                HEAVY_EXPERT_MAX_PROVIDER_FAILOVER_ATTEMPTS, HEAVY_EXPERT_MAX_TOOL_WRITES,
                HEAVY_EXPERT_MAX_BUILD_EXECUTIONS, HEAVY_EXPERT_MAX_REPAIR_ROUNDS));
        return profiles;
    }

    /** 返回饱和降级后的 AGENT_EDIT 配置档。 */
    private static Profile defaultSaturatedAgentEdit() {
        return profile(
                SATURATED_NAME, SATURATED_FIRST_PREVIEW_TIMEOUT, SATURATED_TOTAL_TIMEOUT,
                SATURATED_MODEL_CALL_TIMEOUT, SATURATED_FIRST_PREVIEW_COMPLETION_RESERVE,
                SATURATED_MAX_ROOT_MODEL_ATTEMPTS, SATURATED_MAX_MODEL_TURNS,
                SATURATED_MAX_PROVIDER_FAILOVER_ATTEMPTS, SATURATED_MAX_TOOL_WRITES,
                SATURATED_MAX_BUILD_EXECUTIONS, SATURATED_MAX_REPAIR_ROUNDS);
    }

    /** 返回配置档。 */
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
        private Duration minimumOperationTimeout = MINIMUM_OPERATION_TIMEOUT;
        private Duration firstPreviewCompletionReserve = Duration.ofSeconds(15);
        private int maxRootModelAttempts;
        private int maxModelTurns;
        private int maxProviderFailoverAttempts;
        private int maxToolWrites;
        private int maxBuildExecutions;
        private int maxRepairRounds;

        /** 返回有效。 */
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
                    && withinNonNegative(maxToolWrites, MAX_TOOL_WRITES)
                    && withinNonNegative(maxBuildExecutions, MAX_BUILD_EXECUTIONS)
                    && withinNonNegative(maxRepairRounds, MAX_REPAIR_ROUNDS);
        }

        private boolean positive(Duration value) {
            return value != null && !value.isZero() && !value.isNegative();
        }

        private boolean within(int value, int maximum) {
            return value > 0 && value <= maximum;
        }

        private boolean withinNonNegative(int value, int maximum) {
            return value >= 0 && value <= maximum;
        }
    }
}
