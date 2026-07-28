package com.rush.rushaicodemother.ai.prompt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rush.rushaicodemother.ai.prompt.release.PromptReleaseCapabilities;
import com.rush.rushaicodemother.ai.prompt.release.PromptReleaseRecord;
import com.rush.rushaicodemother.ai.prompt.release.PromptReleaseRuntime;
import com.rush.rushaicodemother.ai.prompt.release.PromptReleaseSpec;
import com.rush.rushaicodemother.ai.prompt.release.PromptReleaseState;
import com.rush.rushaicodemother.config.AiPromptCatalogProperties;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/** 类路径支持的不可变提示目录，具有确定性的稳定/金丝雀版本。 */
@Component
public class ClasspathPromptCatalog implements PromptCatalog, PromptReleaseRuntime {

    private static final int SUPPORTED_SCHEMA_VERSION = 1;
    private static final Pattern KEY_PATTERN = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");
    private static final Pattern VERSION_PATTERN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,31}");

    private final AiPromptCatalogProperties properties;
    private final Map<String, Map<String, Definition>> definitionsByKey;
    private final Map<String, Definition> definitionsByHash;
    private final Map<String, String> bindingToPromptKey;
    private final Map<String, ResolvedRelease> configuredReleases;
    private final PromptReleaseCapabilities capabilities;
    private final AtomicReference<RuntimeState> runtimeState;

    /**
 * 创建{@code Classpath}提示词目录实例并完成必要的依赖和初始状态设置。
 *
 * @param properties 配置属性
 * @param resourceLoader {@code resourceLoader} 对应的调用参数
 * @param objectMapper {@code objectMapper} 对应的调用参数
 */
    public ClasspathPromptCatalog(AiPromptCatalogProperties properties,
                                  ResourceLoader resourceLoader,
                                  ObjectMapper objectMapper) {
        this.properties = properties;
        if (!properties.isEnabled()) {
            this.definitionsByKey = Map.of();
            this.definitionsByHash = Map.of();
            this.bindingToPromptKey = Map.of();
            this.configuredReleases = Map.of();
            this.capabilities = PromptReleaseCapabilities.empty();
            this.runtimeState = new AtomicReference<>(RuntimeState.unmanaged());
            return;
        }
        Manifest manifest = readManifest(resourceLoader, objectMapper);
        LoadedDefinitions loaded = loadDefinitions(manifest, resourceLoader);
        this.definitionsByKey = loaded.byKey();
        this.definitionsByHash = loaded.byHash();
        this.bindingToPromptKey = loadBindings(manifest, definitionsByKey.keySet());
        this.configuredReleases = resolveReleases(manifest);
        this.capabilities = buildCapabilities();
        this.runtimeState = new AtomicReference<>(buildRuntimeState(0L, configuredReleases));
    }

    /**
 * 从候选项中选择{@code Classpath}提示词目录。
 *
 * @param subject {@code subject} 对应的调用参数
 * @return 可选的{@code Classpath}提示词目录；不存在时返回空值
 */
    @Override
    public Optional<PromptSelection> select(PromptRolloutSubject subject) {
        if (subject == null) {
            return Optional.empty();
        }
        String promptKey = bindingToPromptKey.get(subject.bindingKey());
        if (promptKey == null) {
            return Optional.empty();
        }
        return selectByKey(promptKey, subject.cohortKey());
    }

    @Override
    public Optional<PromptSelection> selectByKey(String promptKey, String cohortKey) {
        if (promptKey == null || promptKey.isBlank()) {
            return Optional.empty();
        }
        String normalizedPromptKey = promptKey.trim();
        String normalizedCohortKey = cohortKey == null || cohortKey.isBlank()
                ? "unknown"
                : cohortKey.trim();
        RuntimeState state = runtimeState.get();
        ResolvedRelease release = state.releases().get(normalizedPromptKey);
        if (release == null) {
            return Optional.empty();
        }
        boolean canary = release.canary() != null
                && release.canaryPercentage() > 0
                && bucket(normalizedPromptKey, normalizedCohortKey) < release.canaryPercentage();
        Definition selected = canary ? release.canary() : release.stable();
        return Optional.of(toSelection(
                selected,
                canary ? PromptSelection.Channel.CANARY : PromptSelection.Channel.STABLE,
                state.snapshot()
        ));
    }

    /**
 * 返回{@code identify}。
 *
 * @param promptContent 提示词内容
 * @return 可选的{@code Classpath}提示词目录；不存在时返回空值
 */
    @Override
    public Optional<PromptSelection> identify(String promptContent) {
        String contentHash = PromptDigest.sha256(PromptDigest.normalizeContent(promptContent));
        Definition definition = definitionsByHash.get(contentHash);
        if (definition == null) {
            return Optional.empty();
        }
        RuntimeState state = runtimeState.get();
        ResolvedRelease release = state.releases().get(definition.promptKey());
        PromptSelection.Channel channel = release != null && release.stable().equals(definition)
                ? PromptSelection.Channel.STABLE
                : release != null && definition.equals(release.canary())
                ? PromptSelection.Channel.CANARY
                : PromptSelection.Channel.ARCHIVED;
        return Optional.of(toSelection(definition, channel, state.snapshot()));
    }

    /**
 * 返回快照。
 *
 * @return {@code Classpath}提示词目录
 */
    @Override
    public PromptCatalogSnapshot snapshot() {
        return runtimeState.get().snapshot();
    }

    @Override
    public PromptReleaseCapabilities capabilities() {
        return capabilities;
    }

    /**
 * 返回活动修订版本。
 *
 * @return 计算或处理后的数值结果
 */
    @Override
    public long activeRevision() {
        return runtimeState.get().revision();
    }

    /**
 * 返回预览。
 *
 * @param state 状态
 * @return {@code Classpath}提示词目录
 */
    @Override
    public PromptCatalogSnapshot preview(PromptReleaseState state) {
        if (state == null) {
            throw new IllegalArgumentException("Prompt 发布预演状态不能为空");
        }
        return buildRuntimeState(state.revision(), mergeReleaseOverrides(state)).snapshot();
    }

    /**
 * 返回{@code activate}。
 *
 * @param state 状态
 * @return 满足条件时返回 {@code true}，否则返回 {@code false}
 */
    @Override
    public boolean activate(PromptReleaseState state) {
        if (state == null) {
            throw new IllegalArgumentException("prompt release state is required");
        }
        if (state.revision() < runtimeState.get().revision()) {
            return false;
        }
        RuntimeState candidate = buildRuntimeState(state.revision(), mergeReleaseOverrides(state));
        while (true) {
            RuntimeState current = runtimeState.get();
            if (candidate.revision() < current.revision()) {
                return false;
            }
            if (candidate.revision() == current.revision()) {
                if (candidate.snapshot().equals(current.snapshot())) {
                    return false;
                }
                throw new IllegalStateException(
                        "AI prompt release revision maps to conflicting catalog states");
            }
            if (runtimeState.compareAndSet(current, candidate)) {
                return true;
            }
        }
    }

    /** 读取清单。 */
    private Manifest readManifest(ResourceLoader resourceLoader, ObjectMapper objectMapper) {
        Resource resource = resourceLoader.getResource(properties.getManifest());
        if (!resource.exists()) {
            throw new IllegalStateException("AI prompt catalog manifest does not exist");
        }
        try (InputStream input = resource.getInputStream()) {
            Manifest manifest = objectMapper.readValue(input, Manifest.class);
            if (manifest == null || manifest.schemaVersion() != SUPPORTED_SCHEMA_VERSION) {
                throw new IllegalStateException("AI prompt catalog schema version is unsupported");
            }
            if (manifest.prompts() == null || manifest.prompts().isEmpty()) {
                throw new IllegalStateException("AI prompt catalog has no prompt definitions");
            }
            return manifest;
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("AI prompt catalog manifest cannot be loaded", exception);
        }
    }

    /** 加载{@code Definitions}。 */
    private LoadedDefinitions loadDefinitions(Manifest manifest, ResourceLoader resourceLoader) {
        Map<String, Map<String, Definition>> byKey = new LinkedHashMap<>();
        Map<String, Definition> byHash = new LinkedHashMap<>();
        // 按既定顺序逐项处理，并在达到资源或状态边界时提前结束。
        for (PromptEntry prompt : manifest.prompts()) {
            requireKey(prompt == null ? null : prompt.key());
            if (byKey.containsKey(prompt.key())) {
                throw new IllegalStateException("AI prompt catalog contains a duplicate prompt key");
            }
            if (prompt.versions() == null || prompt.versions().isEmpty()) {
                throw new IllegalStateException("AI prompt catalog prompt has no versions");
            }
            Map<String, Definition> versions = new LinkedHashMap<>();
            for (VersionEntry version : prompt.versions()) {
                Definition definition = loadDefinition(prompt.key(), version, resourceLoader);
                if (versions.putIfAbsent(definition.version(), definition) != null) {
                    throw new IllegalStateException("AI prompt catalog contains a duplicate prompt version");
                }
                Definition duplicateContent = byHash.putIfAbsent(definition.contentHash(), definition);
                if (duplicateContent != null
                        && (!duplicateContent.promptKey().equals(definition.promptKey())
                        || !duplicateContent.version().equals(definition.version()))) {
                    throw new IllegalStateException("AI prompt catalog content hashes must identify one version");
                }
            }
            String defaultVersion = normalizeVersion(prompt.defaultVersion());
            if (!versions.containsKey(defaultVersion)) {
                throw new IllegalStateException("AI prompt catalog default version does not exist");
            }
            byKey.put(prompt.key(), Map.copyOf(versions));
        }
        return new LoadedDefinitions(Map.copyOf(byKey), Map.copyOf(byHash));
    }

    /** 加载{@code Definition}。 */
    private Definition loadDefinition(String promptKey,
                                      VersionEntry version,
                                      ResourceLoader resourceLoader) {
        // 先处理前置条件和快速返回分支，避免无效输入进入核心流程。
        if (version == null) {
            throw new IllegalStateException("AI prompt catalog version is required");
        }
        String normalizedVersion = normalizeVersion(version.version());
        if (version.resource() == null || version.resource().isBlank()
                || version.sha256() == null || !version.sha256().matches("[a-fA-F0-9]{64}")) {
            throw new IllegalStateException("AI prompt catalog version metadata is invalid");
        }
        Resource resource = resourceLoader.getResource(resourceLocation(version.resource()));
        if (!resource.exists()) {
            throw new IllegalStateException("AI prompt catalog resource does not exist");
        }
        try (InputStream input = resource.getInputStream()) {
            String content = PromptDigest.normalizeContent(
                    new String(input.readAllBytes(), StandardCharsets.UTF_8));
            if (content.isBlank()) {
                throw new IllegalStateException("AI prompt catalog resource is blank");
            }
            String contentHash = PromptDigest.sha256(content);
            if (!contentHash.equalsIgnoreCase(version.sha256())) {
                throw new IllegalStateException("AI prompt catalog resource hash does not match manifest");
            }
            return new Definition(promptKey, normalizedVersion, content, contentHash);
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("AI prompt catalog resource cannot be loaded", exception);
        }
    }

    /** 加载{@code Bindings}。 */
    private Map<String, String> loadBindings(Manifest manifest, Set<String> promptKeys) {
        if (manifest.bindings() == null || manifest.bindings().isEmpty()) {
            return Map.of();
        }
        Map<String, String> bindings = new LinkedHashMap<>();
        for (BindingEntry binding : manifest.bindings()) {
            if (binding == null
                    || binding.interfaceName() == null || binding.interfaceName().isBlank()
                    || binding.methodName() == null || binding.methodName().isBlank()
                    || !promptKeys.contains(binding.promptKey())) {
                throw new IllegalStateException("AI prompt catalog binding is invalid");
            }
            String bindingKey = binding.interfaceName().trim() + "#" + binding.methodName().trim();
            if (bindings.putIfAbsent(bindingKey, binding.promptKey()) != null) {
                throw new IllegalStateException("AI prompt catalog contains a duplicate binding");
            }
        }
        return Map.copyOf(bindings);
    }

    /** 根据当前上下文解析{@code Releases}。 */
    private Map<String, ResolvedRelease> resolveReleases(Manifest manifest) {
        Map<String, String> defaultVersions = manifest.prompts().stream()
                .collect(java.util.stream.Collectors.toMap(
                        PromptEntry::key,
                        prompt -> normalizeVersion(prompt.defaultVersion())
                ));
        Map<String, AiPromptCatalogProperties.Release> configured = properties.getReleases() == null
                ? Map.of() : properties.getReleases();
        List<String> unknownKeys = configured.keySet().stream()
                .filter(key -> !definitionsByKey.containsKey(key))
                .toList();
        if (!unknownKeys.isEmpty()) {
            throw new IllegalStateException("AI prompt release references an unknown prompt key");
        }
        Map<String, ResolvedRelease> resolved = new TreeMap<>();
        // 按既定顺序逐项处理，并在达到资源或状态边界时提前结束。
        for (String promptKey : definitionsByKey.keySet()) {
            AiPromptCatalogProperties.Release release = configured.get(promptKey);
            String stableVersion = release == null || release.getStableVersion() == null
                    || release.getStableVersion().isBlank()
                    ? defaultVersions.get(promptKey)
                    : normalizeVersion(release.getStableVersion());
            Definition stable = requireDefinition(promptKey, stableVersion);
            int canaryPercentage = release == null ? 0 : release.getCanaryPercentage();
            String canaryVersion = release == null ? "" : release.getCanaryVersion();
            Definition canary = canaryPercentage == 0
                    ? null
                    : requireDefinition(promptKey, normalizeVersion(canaryVersion));
            if (canary != null && canary.equals(stable)) {
                throw new IllegalStateException("AI prompt canary version must differ from stable version");
            }
            resolved.put(promptKey, new ResolvedRelease(stable, canary, canaryPercentage));
        }
        return Map.copyOf(resolved);
    }

    /** 合并发布{@code Overrides}。 */
    private Map<String, ResolvedRelease> mergeReleaseOverrides(PromptReleaseState state) {
        Map<String, ResolvedRelease> merged = new TreeMap<>(configuredReleases);
        for (Map.Entry<String, PromptReleaseRecord> entry : state.releases().entrySet()) {
            String promptKey = entry.getKey();
            PromptReleaseRecord record = entry.getValue();
            if (record == null || !promptKey.equals(record.promptKey())) {
                throw new IllegalStateException("AI prompt release state contains an invalid record");
            }
            merged.put(promptKey, resolveRelease(promptKey, record.release()));
        }
        return Map.copyOf(merged);
    }

    /** 根据当前上下文解析发布。 */
    private ResolvedRelease resolveRelease(String promptKey, PromptReleaseSpec release) {
        if (release == null || !definitionsByKey.containsKey(promptKey)) {
            throw new IllegalStateException("AI prompt release references an unknown prompt key");
        }
        if (release.canaryPercentage() < 0 || release.canaryPercentage() > 100) {
            throw new IllegalStateException("AI prompt canary percentage is invalid");
        }
        Definition stable = requireDefinition(promptKey, normalizeVersion(release.stableVersion()));
        Definition canary = release.canaryPercentage() == 0
                ? null
                : requireDefinition(promptKey, normalizeVersion(release.canaryVersion()));
        if (canary != null && canary.equals(stable)) {
            throw new IllegalStateException("AI prompt canary version must differ from stable version");
        }
        if (release.canaryPercentage() == 0 && !release.canaryVersion().isBlank()) {
            throw new IllegalStateException("AI prompt canary version requires a positive percentage");
        }
        return new ResolvedRelease(stable, canary, release.canaryPercentage());
    }

    /** 构建并返回运行时状态。 */
    private RuntimeState buildRuntimeState(long revision, Map<String, ResolvedRelease> resolved) {
        Map<String, PromptCatalogSnapshot.PromptRelease> releaseSnapshot = new LinkedHashMap<>();
        StringBuilder canonical = new StringBuilder("prompt-catalog-v1\n")
                .append(properties.getRolloutSalt()).append('\n');
        resolved.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    ResolvedRelease release = entry.getValue();
                    String canaryVersion = release.canary() == null ? "" : release.canary().version();
                    String canaryHash = release.canary() == null ? "" : release.canary().contentHash();
                    releaseSnapshot.put(entry.getKey(), new PromptCatalogSnapshot.PromptRelease(
                            release.stable().version(),
                            release.stable().contentHash(),
                            canaryVersion,
                            canaryHash,
                            release.canaryPercentage()
                    ));
                    canonical.append(entry.getKey()).append('|')
                            .append(release.stable().version()).append('|')
                            .append(release.stable().contentHash()).append('|')
                            .append(canaryVersion).append('|')
                            .append(canaryHash).append('|')
                            .append(release.canaryPercentage()).append('\n');
                });
        return new RuntimeState(
                revision,
                Map.copyOf(resolved),
                new PromptCatalogSnapshot(
                        PromptDigest.sha256(canonical.toString()),
                        Map.copyOf(releaseSnapshot)
                )
        );
    }

    private PromptReleaseCapabilities buildCapabilities() {
        Map<String, Map<String, String>> available = new TreeMap<>();
        definitionsByKey.forEach((promptKey, definitions) -> {
            Map<String, String> versions = new TreeMap<>();
            definitions.forEach((version, definition) ->
                    versions.put(version, definition.contentHash()));
            available.put(promptKey, Map.copyOf(versions));
        });
        return new PromptReleaseCapabilities(Map.copyOf(available));
    }

    private Definition requireDefinition(String promptKey, String version) {
        Definition definition = definitionsByKey.getOrDefault(promptKey, Map.of()).get(version);
        if (definition == null) {
            throw new IllegalStateException("AI prompt release version does not exist");
        }
        return definition;
    }

    private PromptSelection toSelection(Definition definition,
                                        PromptSelection.Channel channel,
                                        PromptCatalogSnapshot activeSnapshot) {
        return new PromptSelection(
                definition.promptKey(),
                definition.version(),
                channel,
                definition.content(),
                definition.contentHash(),
                activeSnapshot.bundleId()
        );
    }

    private int bucket(String promptKey, String cohortKey) {
        String digest = PromptDigest.sha256(
                promptKey + "\n" + properties.getRolloutSalt() + "\n" + cohortKey);
        long value = Long.parseUnsignedLong(digest.substring(0, 16), 16);
        return (int) Long.remainderUnsigned(value, 100L);
    }

    private void requireKey(String key) {
        if (key == null || !KEY_PATTERN.matcher(key).matches()) {
            throw new IllegalStateException("AI prompt catalog key is invalid");
        }
    }

    private String normalizeVersion(String version) {
        String normalized = version == null ? "" : version.trim();
        if (!VERSION_PATTERN.matcher(normalized).matches()) {
            throw new IllegalStateException("AI prompt catalog version is invalid");
        }
        return normalized;
    }

    private String resourceLocation(String resource) {
        String normalized = resource.trim();
        return normalized.contains(":") ? normalized : "classpath:" + normalized;
    }

    private record Definition(String promptKey, String version, String content, String contentHash) {
    }

    private record ResolvedRelease(Definition stable, Definition canary, int canaryPercentage) {
    }

    private record RuntimeState(
            long revision,
            Map<String, ResolvedRelease> releases,
            PromptCatalogSnapshot snapshot
    ) {
        private static RuntimeState unmanaged() {
            return new RuntimeState(0L, Map.of(), PromptCatalogSnapshot.unmanaged());
        }
    }

    private record LoadedDefinitions(
            Map<String, Map<String, Definition>> byKey,
            Map<String, Definition> byHash
    ) {
    }

    private record Manifest(
            int schemaVersion,
            List<PromptEntry> prompts,
            List<BindingEntry> bindings
    ) {
    }

    private record PromptEntry(
            String key,
            String defaultVersion,
            List<VersionEntry> versions
    ) {
    }

    private record VersionEntry(String version, String resource, String sha256) {
    }

    private record BindingEntry(String interfaceName, String methodName, String promptKey) {
    }
}
