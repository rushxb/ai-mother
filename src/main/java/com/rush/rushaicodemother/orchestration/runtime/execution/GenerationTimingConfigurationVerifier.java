package com.rush.rushaicodemother.orchestration.runtime.execution;

import com.rush.rushaicodemother.orchestration.router.GenerationMode;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** 在应用接收生成任务前，验证路由 SLA 能够容纳完整的模型与交付窗口。 */
@Component
@RequiredArgsConstructor
public class GenerationTimingConfigurationVerifier implements SmartInitializingSingleton {

    private final GenerationSlaProperties slaProperties;
    private final GenerationRuntimeProperties runtimeProperties;
    private final GenerationStageAdmissionProperties stageAdmissionProperties;

    @Override
    public void afterSingletonsInstantiated() {
        Duration minimumModelTurn = stageAdmissionProperties.getModelTurnMinimum();
        Duration minimumTaskWindow = stageAdmissionProperties.maximumModelTurnMinimumRequired();
        List<String> violations = new ArrayList<>();

        for (GenerationMode mode : GenerationMode.values()) {
            validateProfile(
                    "app.generation-sla.profiles." + mode.name(),
                    slaProperties.profile(mode),
                    minimumModelTurn,
                    minimumTaskWindow,
                    violations
            );
        }
        validateProfile(
                "app.generation-sla.saturated-agent-edit",
                slaProperties.getSaturatedAgentEdit(),
                minimumModelTurn,
                minimumTaskWindow,
                violations
        );
        validateLegacyRuntime(minimumModelTurn, minimumTaskWindow, violations);

        if (!violations.isEmpty()) {
            throw new IllegalStateException(
                    "生成时长配置无法为完整交付保留必要窗口：" + String.join("；", violations));
        }
    }

    private void validateProfile(String propertyPrefix,
                                 GenerationSlaProperties.Profile profile,
                                 Duration minimumModelTurn,
                                 Duration minimumTaskWindow,
                                 List<String> violations) {
        if (profile.getModelCallTimeout().compareTo(minimumModelTurn) < 0) {
            violations.add(propertyPrefix + ".model-call-timeout 必须不少于 "
                    + minimumModelTurn.toMillis() + "ms");
        }
        if (profile.getTotalTimeout().compareTo(minimumTaskWindow) < 0) {
            violations.add(propertyPrefix + ".total-timeout 必须不少于 "
                    + minimumTaskWindow.toMillis() + "ms");
        }
    }

    private void validateLegacyRuntime(Duration minimumModelTurn,
                                       Duration minimumTaskWindow,
                                       List<String> violations) {
        if (runtimeProperties.getModelCallTimeout().compareTo(minimumModelTurn) < 0) {
            violations.add("app.generation-runtime.model-call-timeout 必须不少于 "
                    + minimumModelTurn.toMillis() + "ms");
        }
        if (runtimeProperties.getTaskTimeout().compareTo(minimumTaskWindow) < 0) {
            violations.add("app.generation-runtime.task-timeout 必须不少于 "
                    + minimumTaskWindow.toMillis() + "ms");
        }
    }
}
