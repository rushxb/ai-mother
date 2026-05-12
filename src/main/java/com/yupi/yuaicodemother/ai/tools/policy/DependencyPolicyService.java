package com.yupi.yuaicodemother.ai.tools.policy;

import cn.hutool.core.util.StrUtil;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class DependencyPolicyService {

    public static final String DEPENDENCIES = "dependencies";
    public static final String DEV_DEPENDENCIES = "devDependencies";

    private static final Pattern PACKAGE_NAME_PATTERN = Pattern.compile("^(?:@[a-z0-9][a-z0-9._-]*/)?[a-z0-9][a-z0-9._-]*$");
    private static final Set<String> VALID_DEPENDENCY_TYPES = Set.of(DEPENDENCIES, DEV_DEPENDENCIES);
    private static final Set<String> FORBIDDEN_LIFECYCLE_SCRIPTS = Set.of(
            "preinstall", "postinstall", "prepare", "prepublish", "prepack", "postpack"
    );
    private static final List<String> DANGEROUS_COMMAND_FRAGMENTS = List.of(
            "rm -rf", "curl |", "wget |", "powershell", "cmd /c", "invoke-webrequest",
            " iwr ", "chmod 777", "sudo", "del /s", "format "
    );

    public PolicyDecision validateAddOrUpdate(String packageName, String version, String dependencyType, String reason) {
        PolicyDecision packageDecision = validatePackageName(packageName);
        if (!packageDecision.allowed()) {
            return packageDecision;
        }
        if (StrUtil.isBlank(version)) {
            return PolicyDecision.rejected("依赖版本不能为空");
        }
        PolicyDecision typeDecision = validateDependencyType(dependencyType);
        if (!typeDecision.allowed()) {
            return typeDecision;
        }
        if (StrUtil.isBlank(reason)) {
            return PolicyDecision.rejected("新增或更新依赖必须提供 reason");
        }
        return PolicyDecision.allowed("dependency policy approved", Map.of(
                "package", packageName,
                "version", version,
                "dependencyType", normalizeDependencyType(dependencyType),
                "reason", reason
        ));
    }

    public PolicyDecision validateRemove(String packageName, String dependencyType) {
        PolicyDecision packageDecision = validatePackageName(packageName);
        if (!packageDecision.allowed()) {
            return packageDecision;
        }
        return validateDependencyType(dependencyType);
    }

    public PolicyDecision validateScript(String scriptName, String scriptCommand) {
        if (StrUtil.isBlank(scriptName) || StrUtil.isBlank(scriptCommand)) {
            return PolicyDecision.rejected("脚本名称和脚本命令不能为空");
        }
        String normalizedName = scriptName.trim().toLowerCase();
        if (FORBIDDEN_LIFECYCLE_SCRIPTS.contains(normalizedName)) {
            return PolicyDecision.rejected("禁止修改高风险生命周期脚本: " + scriptName);
        }
        String normalizedCommand = " " + scriptCommand.toLowerCase().replaceAll("\\s+", " ") + " ";
        for (String fragment : DANGEROUS_COMMAND_FRAGMENTS) {
            if (normalizedCommand.contains(fragment)) {
                return PolicyDecision.rejected("脚本包含高风险命令片段: " + fragment.trim());
            }
        }
        return PolicyDecision.allowed("script policy approved", Map.of(
                "scriptName", scriptName,
                "scriptCommand", scriptCommand
        ));
    }

    public PolicyDecision validateInstall(String actionSource) {
        return PolicyDecision.allowed("install approved by dependency policy", Map.of(
                "actionSource", StrUtil.blankToDefault(actionSource, "manual")
        ));
    }

    public String normalizeDependencyType(String dependencyType) {
        if (StrUtil.isBlank(dependencyType)) {
            return DEPENDENCIES;
        }
        String trimmed = dependencyType.trim();
        if (VALID_DEPENDENCY_TYPES.contains(trimmed)) {
            return trimmed;
        }
        throw new IllegalArgumentException("依赖分组仅支持 dependencies 或 devDependencies");
    }

    private PolicyDecision validatePackageName(String packageName) {
        if (StrUtil.isBlank(packageName)) {
            return PolicyDecision.rejected("依赖名称不能为空");
        }
        String normalized = packageName.trim();
        if (!PACKAGE_NAME_PATTERN.matcher(normalized).matches()) {
            return PolicyDecision.rejected("非法依赖名称: " + packageName);
        }
        if (normalized.contains("..") || normalized.contains("\\") || normalized.startsWith("http") || normalized.contains(":")) {
            return PolicyDecision.rejected("非法依赖名称: " + packageName);
        }
        return PolicyDecision.allowed("package name approved", Map.of("package", normalized));
    }

    private PolicyDecision validateDependencyType(String dependencyType) {
        try {
            String normalized = normalizeDependencyType(dependencyType);
            return PolicyDecision.allowed("dependency type approved", Map.of("dependencyType", normalized));
        } catch (IllegalArgumentException e) {
            return PolicyDecision.rejected(e.getMessage());
        }
    }

    public record PolicyDecision(boolean allowed, String reason, Map<String, Object> metadata) {

        public static PolicyDecision allowed(String reason, Map<String, Object> metadata) {
            return new PolicyDecision(true, reason, metadata == null ? Map.of() : metadata);
        }

        public static PolicyDecision rejected(String reason) {
            return new PolicyDecision(false, reason, Map.of());
        }
    }
}
