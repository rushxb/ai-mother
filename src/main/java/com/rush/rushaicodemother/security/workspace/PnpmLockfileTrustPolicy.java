package com.rush.rushaicodemother.security.workspace;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 对当前 pnpm 锁文件执行有界、无对象实例化的安全语义校验。
 *
 * <p>只接受单根 importer、registry 包和完整 SHA-512 摘要；URL、Git、本地路径、patch、
 * catalog 与 override 等会改变依赖来源的语义全部 fail-closed。</p>
 */
final class PnpmLockfileTrustPolicy {

    private static final long MAX_LOCKFILE_BYTES = 4L * 1024 * 1024;
    private static final int MAX_LOCKFILE_CODE_POINTS = 4 * 1024 * 1024;
    private static final int MAX_NESTING_DEPTH = 64;
    private static final int SHA512_DIGEST_BYTES = 64;
    private static final List<String> EXTERNAL_RESOLUTION_PREFIXES = List.of(
            "file:",
            "link:",
            "portal:",
            "http:",
            "https:",
            "git:",
            "git+",
            "github:",
            "gitlab:",
            "bitbucket:",
            "workspace:",
            "catalog:",
            "catalogs:",
            "patch:"
    );
    private static final List<String> DEPENDENCY_SECTIONS = List.of(
            "dependencies",
            "devDependencies",
            "optionalDependencies",
            "peerDependencies"
    );
    private static final Set<String> FORBIDDEN_RESOLUTION_CONTROL_KEYS = Set.of(
            "patchedDependencies",
            "overrides",
            "catalogs",
            "catalogMode"
    );
    private static final Set<String> REGISTRY_RESOLUTION_KEYS = Set.of("integrity");

    /** 返回空字符串表示允许，否则返回稳定的机器可读拒绝原因。 */
    String validate(Path lockfile) {
        if (!Files.exists(lockfile, LinkOption.NOFOLLOW_LINKS)) {
            return "";
        }
        if (Files.isSymbolicLink(lockfile)
                || !Files.isRegularFile(lockfile, LinkOption.NOFOLLOW_LINKS)) {
            return "generated_workspace_lockfile_not_regular";
        }
        try {
            if (Files.size(lockfile) > MAX_LOCKFILE_BYTES) {
                return "generated_workspace_lockfile_too_large";
            }
            Object document;
            try (Reader reader = Files.newBufferedReader(lockfile, StandardCharsets.UTF_8)) {
                document = createYamlParser().load(reader);
            }
            if (!(document instanceof Map<?, ?> root)) {
                return "generated_workspace_lockfile_invalid";
            }
            if (!"9.0".equals(String.valueOf(root.get("lockfileVersion")))) {
                return "generated_workspace_lockfile_version_unsupported";
            }
            if (FORBIDDEN_RESOLUTION_CONTROL_KEYS.stream().anyMatch(root::containsKey)) {
                return "generated_workspace_lockfile_resolution_controls_forbidden";
            }
            String importerBlocker = validateImporterScope(root);
            if (!importerBlocker.isEmpty()) {
                return importerBlocker;
            }
            String packageBlocker = validatePackageResolutions(root);
            if (!packageBlocker.isEmpty()) {
                return packageBlocker;
            }
            return validateSnapshots(root);
        } catch (IOException | RuntimeException exception) {
            return "generated_workspace_lockfile_invalid";
        }
    }

    private String validateImporterScope(Map<?, ?> root) {
        if (!root.containsKey("importers")) {
            return "";
        }
        Object importersValue = root.get("importers");
        if (!(importersValue instanceof Map<?, ?> importers)) {
            return "generated_workspace_lockfile_invalid";
        }
        if (importers.keySet().stream().anyMatch(key -> !".".equals(key))) {
            return "generated_workspace_lockfile_workspace_scope_forbidden";
        }
        for (Object importerValue : importers.values()) {
            if (!(importerValue instanceof Map<?, ?> importer)) {
                return "generated_workspace_lockfile_invalid";
            }
            String dependencyBlocker = validateImporterDependencies(importer);
            if (!dependencyBlocker.isEmpty()) {
                return dependencyBlocker;
            }
        }
        return "";
    }

    private String validateImporterDependencies(Map<?, ?> importer) {
        for (String sectionName : DEPENDENCY_SECTIONS) {
            if (!importer.containsKey(sectionName)) {
                continue;
            }
            Object dependenciesValue = importer.get(sectionName);
            if (!(dependenciesValue instanceof Map<?, ?> dependencies)) {
                return "generated_workspace_lockfile_invalid";
            }
            for (Object dependencyValue : dependencies.values()) {
                if (!(dependencyValue instanceof Map<?, ?> dependency)) {
                    return "generated_workspace_lockfile_invalid";
                }
                Object specifier = dependency.get("specifier");
                Object version = dependency.get("version");
                if (!(specifier instanceof String) || !(version instanceof String)) {
                    return "generated_workspace_lockfile_invalid";
                }
                if (isExternalResolution((String) specifier)
                        || isExternalResolution((String) version)) {
                    return "generated_workspace_lockfile_external_resolution";
                }
            }
        }
        return "";
    }

    private String validatePackageResolutions(Map<?, ?> root) {
        if (!root.containsKey("packages")) {
            return "";
        }
        Object packagesValue = root.get("packages");
        if (!(packagesValue instanceof Map<?, ?> packages)) {
            return "generated_workspace_lockfile_invalid";
        }
        for (Map.Entry<?, ?> packageEntry : packages.entrySet()) {
            if (!(packageEntry.getKey() instanceof String packageIdentity)
                    || isExternalResolution(packageIdentity)) {
                return "generated_workspace_lockfile_external_resolution";
            }
            Object packageValue = packageEntry.getValue();
            if (!(packageValue instanceof Map<?, ?> packageDefinition)) {
                return "generated_workspace_lockfile_invalid";
            }
            Object resolutionValue = packageDefinition.get("resolution");
            if (!(resolutionValue instanceof Map<?, ?> resolution)) {
                return "generated_workspace_lockfile_integrity_missing";
            }
            if (resolution.keySet().stream().anyMatch(key -> !REGISTRY_RESOLUTION_KEYS.contains(key))) {
                return "generated_workspace_lockfile_external_resolution";
            }
            Object integrityValue = resolution.get("integrity");
            if (!(integrityValue instanceof String integrity)) {
                return "generated_workspace_lockfile_integrity_missing";
            }
            if (!isValidSha512Integrity(integrity)) {
                return "generated_workspace_lockfile_integrity_invalid";
            }
        }
        return "";
    }

    private String validateSnapshots(Map<?, ?> root) {
        if (!root.containsKey("snapshots")) {
            return "";
        }
        Object snapshotsValue = root.get("snapshots");
        if (!(snapshotsValue instanceof Map<?, ?> snapshots)) {
            return "generated_workspace_lockfile_invalid";
        }
        for (Map.Entry<?, ?> snapshotEntry : snapshots.entrySet()) {
            if (!(snapshotEntry.getKey() instanceof String packageIdentity)
                    || isExternalResolution(packageIdentity)) {
                return "generated_workspace_lockfile_external_resolution";
            }
            if (!(snapshotEntry.getValue() instanceof Map<?, ?> snapshot)) {
                return "generated_workspace_lockfile_invalid";
            }
            String dependencyBlocker = validateSnapshotDependencies(snapshot);
            if (!dependencyBlocker.isEmpty()) {
                return dependencyBlocker;
            }
        }
        return "";
    }

    private String validateSnapshotDependencies(Map<?, ?> snapshot) {
        for (String sectionName : DEPENDENCY_SECTIONS) {
            if (!snapshot.containsKey(sectionName)) {
                continue;
            }
            Object dependenciesValue = snapshot.get(sectionName);
            if (!(dependenciesValue instanceof Map<?, ?> dependencies)) {
                return "generated_workspace_lockfile_invalid";
            }
            for (Object dependencyValue : dependencies.values()) {
                if (!(dependencyValue instanceof String version)) {
                    return "generated_workspace_lockfile_invalid";
                }
                if (isExternalResolution(version)) {
                    return "generated_workspace_lockfile_external_resolution";
                }
            }
        }
        return "";
    }

    private boolean isValidSha512Integrity(String integrity) {
        if (integrity == null || !integrity.startsWith("sha512-")) {
            return false;
        }
        try {
            return Base64.getDecoder().decode(integrity.substring("sha512-".length())).length
                    == SHA512_DIGEST_BYTES;
        } catch (IllegalArgumentException invalidBase64) {
            return false;
        }
    }

    private boolean isExternalResolution(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return normalized.isEmpty()
                || normalized.contains("://")
                || normalized.startsWith("/")
                || normalized.startsWith("./")
                || normalized.startsWith("../")
                || normalized.contains("\\")
                || EXTERNAL_RESOLUTION_PREFIXES.stream().anyMatch(normalized::startsWith);
    }

    private Yaml createYamlParser() {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setAllowRecursiveKeys(false);
        options.setMaxAliasesForCollections(0);
        options.setNestingDepthLimit(MAX_NESTING_DEPTH);
        options.setCodePointLimit(MAX_LOCKFILE_CODE_POINTS);
        return new Yaml(new SafeConstructor(options));
    }
}
