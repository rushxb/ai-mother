package com.rush.rushaicodemother.orchestration.benchmark.evidence;

import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** 根据实际生效的生成语义配置计算稳定指纹，不信任外部手工标签。 */
@Component
public class GenerationRuntimeConfigurationFingerprintService {

    private static final String SCHEMA = "generation-runtime-configuration-v1|";
    private static final String APPLICATION_CONFIG = "classpath:application.yml";

    private static final List<String> INCLUDED_PREFIXES = List.of(
            "app.database-resource.",
            "app.ai-model-runtime.",
            "app.ai-model-capacity.",
            "app.ai-model-circuit-breaker.",
            "app.generation-sla.",
            "app.generation-stage-admission.",
            "app.ai-context-pack.",
            "app.generation-memory-context.",
            "app.generation-runtime.",
            "app.generation-project-context.",
            "app.edit-locator.",
            "app.patch-execution.",
            "app.ai-tool-workspace.",
            "app.ai-tool-loop-guard.",
            "app.ai-agent-productivity.",
            "app.node-toolchain.",
            "app.go-toolchain.",
            "app.project-command.",
            "app.workspace-file-system.",
            "app.dependency-install.",
            "app.template-materialization.",
            "app.generation-benchmark.browser-grading.",
            "app.generation-benchmark.backend-grading.",
            "app.generation-benchmark.release-gate."
    );

    private static final Set<String> INCLUDED_PROPERTIES = Set.of(
            "app.user-credit.tokens-per-credit",
            "app.memory.long-term.enabled",
            "app.memory.long-term.fallback-max-entries",
            "app.memory.long-term.fallback-retention",
            "app.memory.long-term.default-top-k",
            "app.memory.long-term.minimum-score",
            "app.chat-memory.completed-tool-arguments-max-chars",
            "app.ai-prompt-catalog.rollout-salt",
            "app.generated-code-sandbox.mode",
            "app.generated-code-sandbox.container.image",
            "app.generated-code-sandbox.container.user",
            "app.generated-code-sandbox.container.memory",
            "app.generated-code-sandbox.container.tmpfs-size",
            "app.generated-code-sandbox.container.go-build-tmpfs-size",
            "app.generated-code-sandbox.container.cpus",
            "app.generated-code-sandbox.container.pids-limit",
            "app.generated-code-sandbox.container.read-only-root",
            "app.generation-benchmark.task-timeout",
            "app.generation-benchmark.cancellation-grace-timeout",
            "app.generation-benchmark.terminal-poll-interval",
            "app.generation-benchmark.first-preview-observation-timeout",
            "app.generation-task-snapshot.replay-safe-start-checkpoint-elision-enabled",
            "app.generation-task-snapshot.replay-safe-completion-checkpoint-coalescing-enabled",
            "app.generation-task-snapshot.replay-safe-completion-checkpoint-interval",
            "app.generation-event-stream.delta-coalescing-enabled",
            "app.generation-event-stream.delta-flush-interval",
            "app.generation-event-stream.delta-max-chars",
            "app.dev-server.runtime.startup-timeout",
            "app.dev-server.runtime.readiness-poll-interval"
    );

    private static final Set<String> EXCLUDED_PROPERTIES = Set.of(
            "app.ai-model-runtime.generation-log-requests",
            "app.ai-model-runtime.generation-log-responses",
            "app.ai-model-runtime.routing-log-requests",
            "app.ai-model-runtime.routing-log-responses",
            "app.ai-model-capacity.key-prefix",
            "app.generation-benchmark.browser-grading.enabled",
            "app.generation-benchmark.backend-grading.enabled",
            "app.generation-benchmark.backend-grading.workspace-root"
    );

    private final ConfigurableEnvironment environment;
    private final Map<String, String> configuredDefaults;

    public GenerationRuntimeConfigurationFingerprintService(
            ConfigurableEnvironment environment,
            ResourceLoader resourceLoader
    ) {
        this.environment = environment;
        this.configuredDefaults = discoverConfiguration(resourceLoader);
    }

    public String currentFingerprint() {
        StringBuilder canonical = new StringBuilder(SCHEMA);
        configuredDefaults.forEach((name, defaultValue) -> {
            ReleaseCandidateFingerprint.appendField(canonical, name);
            ReleaseCandidateFingerprint.appendField(
                    canonical,
                    resolveValue(name, defaultValue)
            );
        });
        return ReleaseCandidateFingerprint.sha256(canonical.toString());
    }

    private Map<String, String> discoverConfiguration(ResourceLoader resourceLoader) {
        Resource resource = resourceLoader.getResource(APPLICATION_CONFIG);
        Map<String, String> discovered = new TreeMap<>();
        try {
            for (PropertySource<?> source : new YamlPropertySourceLoader()
                    .load("generation-runtime-fingerprint", resource)) {
                if (!(source instanceof EnumerablePropertySource<?> enumerable)) {
                    continue;
                }
                for (String name : enumerable.getPropertyNames()) {
                    if (isIncluded(name)) {
                        Object value = source.getProperty(name);
                        discovered.put(name, value == null ? "" : value.toString());
                    }
                }
            }
        } catch (IOException failure) {
            throw new IllegalStateException("无法读取生成运行配置定义", failure);
        }
        if (discovered.isEmpty()) {
            throw new IllegalStateException("生成运行配置指纹清单为空");
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(discovered));
    }

    private boolean isIncluded(String name) {
        if (EXCLUDED_PROPERTIES.contains(name)) {
            return false;
        }
        return INCLUDED_PROPERTIES.contains(name)
                || INCLUDED_PREFIXES.stream().anyMatch(name::startsWith);
    }

    private String resolveValue(String name, String defaultValue) {
        String value = environment.getProperty(name);
        String candidate = value == null ? defaultValue : value;
        try {
            return environment.resolveRequiredPlaceholders(candidate).trim();
        } catch (IllegalArgumentException unresolved) {
            throw new IllegalStateException("生成运行配置存在未解析的占位符: " + name, unresolved);
        }
    }
}
