package com.rush.rushaicodemother.orchestration.patch;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 生成工作区的最终写入信任边界。
 *
 * <p>该深模块位于所有 patch 写入入口共享的校验 seam：拒绝包管理器控制文件，
 * 并对允许修改的 {@code package.json} 执行 fail-closed 语义校验。工具写入、Agent patch
 * 和自动修复因此不能通过切换写入入口绕过同一策略。</p>
 */
@Component
public class GeneratedWorkspaceTrustPolicy {

    private static final Set<String> FORBIDDEN_CONTROL_FILES = Set.of(
            ".pnpmfile.mjs",
            ".pnpmfile.cjs",
            ".npmrc",
            "pnpm-workspace.yaml",
            "pnpm-workspace.yml",
            "pnpm-lock.yaml",
            "package-lock.json",
            "npm-shrinkwrap.json",
            "yarn.lock",
            ".yarnrc",
            ".yarnrc.yml",
            "bun.lock",
            "bun.lockb"
    );
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
        if (!appliesTo(relativePath)) {
            return "";
        }
        String normalizedPath = normalizePath(relativePath);
        if (FORBIDDEN_CONTROL_FILES.contains(fileName(normalizedPath))) {
            return "generated_workspace_forbidden_control_file:" + normalizedPath;
        }
        if (candidateContent == null || candidateContent.isBlank()) {
            return "executable_manifest_empty";
        }
        JsonNode manifest;
        try {
            manifest = OBJECT_MAPPER.readTree(candidateContent);
        } catch (Exception invalidJson) {
            return "executable_manifest_invalid_json";
        }
        if (manifest == null || !manifest.isObject()) {
            return "executable_manifest_not_object";
        }

        String scriptBlocker = validateScripts(manifest.path("scripts"));
        if (!scriptBlocker.isEmpty()) {
            return scriptBlocker;
        }
        String dependencyBlocker = validateDependencies(manifest);
        if (!dependencyBlocker.isEmpty()) {
            return dependencyBlocker;
        }
        return validatePackageManager(manifest.get("packageManager"));
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

    private static String normalizePath(String relativePath) {
        return relativePath.replace('\\', '/').toLowerCase(Locale.ROOT);
    }

    private static String fileName(String normalizedPath) {
        int separatorIndex = normalizedPath.lastIndexOf('/');
        return separatorIndex < 0 ? normalizedPath : normalizedPath.substring(separatorIndex + 1);
    }
}
