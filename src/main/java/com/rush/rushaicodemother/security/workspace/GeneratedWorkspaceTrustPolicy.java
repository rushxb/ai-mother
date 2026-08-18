package com.rush.rushaicodemother.security.workspace;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 生成工作区的统一信任内核。
 *
 * <p>该深模块同时服务写入、离线 Benchmark 与依赖安装入口：拒绝包管理器控制文件，
 * 并对允许修改或即将执行的 {@code package.json} 执行 fail-closed 语义校验。
 * 工具写入、Agent patch、自动修复和磁盘残留因此共享同一套安全语义。</p>
 */
@Component
public class GeneratedWorkspaceTrustPolicy {

    private static final long MAX_PACKAGE_MANIFEST_BYTES = 256 * 1024L;
    private static final Set<String> INSTALL_ACTIVE_CONTROL_FILES = Set.of(
            ".pnpmfile.mjs",
            ".pnpmfile.cjs",
            ".npmrc",
            "pnpm-workspace.yaml",
            "pnpm-workspace.yml"
    );
    private static final Set<String> GENERATED_ONLY_CONTROL_FILES = Set.of(
            "pnpm-lock.yaml",
            "package-lock.json",
            "npm-shrinkwrap.json",
            "yarn.lock",
            ".yarnrc",
            ".yarnrc.yml",
            "bun.lock",
            "bun.lockb"
    );
    private static final Set<String> FORBIDDEN_CONTROL_FILES = mergeControlFiles();
    private static final Set<String> FORBIDDEN_LIFECYCLE_SCRIPTS = Set.of(
            "preinstall",
            "install",
            "postinstall",
            "prepare",
            "prepublish",
            "prepublishonly",
            "publish",
            "postpublish",
            "prepack",
            "postpack"
    );
    private static final List<String> DEPENDENCY_SECTIONS = List.of(
            "dependencies",
            "devDependencies",
            "optionalDependencies",
            "peerDependencies"
    );
    private static final List<String> FORBIDDEN_DEPENDENCY_PREFIXES = List.of(
            "file:",
            "link:",
            "portal:",
            "http:",
            "https:",
            "git:",
            "git+",
            "git@",
            "github:",
            "gitlab:",
            "bitbucket:",
            "workspace:",
            "catalog:",
            "catalogs:",
            "patch:"
    );
    private static final ObjectMapper OBJECT_MAPPER = createObjectMapper();
    private static final Pattern PINNED_PNPM_VERSION = Pattern.compile(
            "^pnpm@[0-9]+\\.[0-9]+\\.[0-9]+(?:-[0-9a-z.-]+)?(?:\\+[0-9a-z.-]+)?$");

    public boolean appliesTo(String relativePath) {
        if (relativePath == null) {
            return false;
        }
        String normalizedPath = normalizePath(relativePath);
        return FORBIDDEN_CONTROL_FILES.contains(fileName(normalizedPath))
                || "package.json".equals(fileName(normalizedPath));
    }

    /** 返回空字符串表示允许，否则返回稳定的机器可读拒绝原因。 */
    public String validate(String relativePath, String candidateContent) {
        return validateAll(relativePath, candidateContent).stream()
                .findFirst()
                .orElse("");
    }

    /**
     * 返回候选文件的全部拒绝原因。生产写入可继续取首条 fail-fast，
     * Benchmark 等离线验证场景则能保留完整安全证据，二者共享同一解析与规则实现。
     */
    public List<String> validateAll(String relativePath, String candidateContent) {
        if (!appliesTo(relativePath)) {
            return List.of();
        }
        String normalizedPath = normalizePath(relativePath);
        if (FORBIDDEN_CONTROL_FILES.contains(fileName(normalizedPath))) {
            return List.of("generated_workspace_forbidden_control_file:" + normalizedPath);
        }
        if (candidateContent == null || candidateContent.isBlank()) {
            return List.of("executable_manifest_empty");
        }
        JsonNode manifest;
        try {
            manifest = OBJECT_MAPPER.readTree(candidateContent);
        } catch (Exception invalidJson) {
            return List.of("executable_manifest_invalid_json");
        }
        if (manifest == null || !manifest.isObject()) {
            return List.of("executable_manifest_not_object");
        }

        List<String> blockers = new ArrayList<>(3);
        String scriptBlocker = validateScripts(manifest.path("scripts"));
        if (!scriptBlocker.isEmpty()) {
            blockers.add(scriptBlocker);
        }
        String dependencyBlocker = validateDependencies(manifest);
        if (!dependencyBlocker.isEmpty()) {
            blockers.add(dependencyBlocker);
        }
        String packageManagerBlocker = validatePackageManager(manifest.get("packageManager"));
        if (!packageManagerBlocker.isEmpty()) {
            blockers.add(packageManagerBlocker);
        }
        return List.copyOf(blockers);
    }

    /**
     * 在 pnpm 读取项目配置前复核工作区当前状态，关闭模板残留、人工写入和历史旁路。
     *
     * <p>锁文件由受信模板和明确的安装模式管理，因此这里不因锁文件存在而拒绝；
     * 但会拒绝能够改变 registry、认证、代理、工作区范围或执行 hook 的项目级控制文件。</p>
     *
     * @param projectRoot 已解析为真实路径的项目根目录
     * @return 空字符串表示允许，否则返回稳定、无敏感内容的机器可读拒绝原因
     */
    public String validateDependencyInstallWorkspace(Path projectRoot) {
        if (projectRoot == null) {
            return "generated_workspace_project_root_missing";
        }
        Path packageManifest = projectRoot.resolve("package.json");
        if (Files.isSymbolicLink(packageManifest)
                || !Files.isRegularFile(packageManifest, LinkOption.NOFOLLOW_LINKS)) {
            return "generated_workspace_manifest_not_regular";
        }

        try {
            if (Files.size(packageManifest) > MAX_PACKAGE_MANIFEST_BYTES) {
                return "executable_manifest_too_large";
            }
            String manifestContent = Files.readString(packageManifest, StandardCharsets.UTF_8);
            List<String> manifestBlockers = validateAll("package.json", manifestContent);
            if (!manifestBlockers.isEmpty()) {
                return manifestBlockers.getFirst();
            }
        } catch (IOException exception) {
            return "generated_workspace_manifest_unreadable";
        }

        for (String controlFileName : INSTALL_ACTIVE_CONTROL_FILES) {
            Path controlFile = projectRoot.resolve(controlFileName);
            if (Files.isSymbolicLink(controlFile)
                    || Files.exists(controlFile, LinkOption.NOFOLLOW_LINKS)) {
                return "generated_workspace_forbidden_control_file:" + controlFileName;
            }
        }
        return "";
    }

    public String validateDeletion(String relativePath) {
        if (!appliesTo(relativePath)) {
            return "";
        }
        return "generated_workspace_control_file_delete_forbidden:" + normalizePath(relativePath);
    }

    private String validateScripts(JsonNode scripts) {
        if (scripts.isMissingNode() || scripts.isNull()) {
            return "";
        }
        if (!scripts.isObject()) {
            return "executable_manifest_scripts_not_object";
        }
        var names = scripts.fieldNames();
        while (names.hasNext()) {
            String normalizedName = names.next().toLowerCase(Locale.ROOT);
            if (FORBIDDEN_LIFECYCLE_SCRIPTS.contains(normalizedName)) {
                return "executable_manifest_forbidden_lifecycle:" + normalizedName;
            }
        }
        return "";
    }

    private String validateDependencies(JsonNode manifest) {
        for (String sectionName : DEPENDENCY_SECTIONS) {
            JsonNode dependencies = manifest.get(sectionName);
            if (dependencies == null || dependencies.isNull()) {
                continue;
            }
            if (!dependencies.isObject()) {
                return "executable_manifest_dependencies_not_object:" + sectionName;
            }
            for (var dependency : dependencies.properties()) {
                if (!dependency.getValue().isTextual()) {
                    return "executable_manifest_dependency_version_invalid:" + dependency.getKey();
                }
                String version = dependency.getValue().asText().trim().toLowerCase(Locale.ROOT);
                if (FORBIDDEN_DEPENDENCY_PREFIXES.stream().anyMatch(version::startsWith)
                        || version.contains("://")) {
                    return "executable_manifest_forbidden_dependency_source:" + dependency.getKey();
                }
            }
        }
        return "";
    }

    private String validatePackageManager(JsonNode packageManager) {
        if (packageManager == null || packageManager.isNull()) {
            return "";
        }
        if (!packageManager.isTextual()) {
            return "executable_manifest_package_manager_invalid";
        }
        String normalized = packageManager.asText().trim().toLowerCase(Locale.ROOT);
        if (!normalized.startsWith("pnpm@")) {
            return "executable_manifest_package_manager_unsupported";
        }
        return PINNED_PNPM_VERSION.matcher(normalized).matches()
                ? ""
                : "executable_manifest_package_manager_version_invalid";
    }

    private static ObjectMapper createObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        mapper.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        return mapper;
    }

    private static Set<String> mergeControlFiles() {
        LinkedHashSet<String> controlFiles = new LinkedHashSet<>(INSTALL_ACTIVE_CONTROL_FILES);
        controlFiles.addAll(GENERATED_ONLY_CONTROL_FILES);
        return Set.copyOf(controlFiles);
    }

    private static String normalizePath(String relativePath) {
        return relativePath.replace('\\', '/').toLowerCase(Locale.ROOT);
    }

    private static String fileName(String normalizedPath) {
        int separatorIndex = normalizedPath.lastIndexOf('/');
        return separatorIndex < 0 ? normalizedPath : normalizedPath.substring(separatorIndex + 1);
    }
}
