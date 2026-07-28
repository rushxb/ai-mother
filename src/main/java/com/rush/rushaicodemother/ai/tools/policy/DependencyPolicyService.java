package com.rush.rushaicodemother.ai.tools.policy;

import cn.hutool.core.util.StrUtil;
import com.rush.rushaicodemother.ai.tools.ToolInputException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 依赖策略服务实现。
 */
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

    /**
 * 校验{@code ate}{@code Add}{@code Or}{@code Update}是否有效。
 *
 * @param packageName 依赖包名称
 * @param version 版本
 * @param dependencyType 依赖类型
 * @param reason 原因
 * @return {@code ate}{@code Add}{@code Or}{@code Update}
 */
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

    /**
 * 校验{@code ate}{@code Remove}是否有效。
 *
 * @param packageName 依赖包名称
 * @param dependencyType 依赖类型
 * @return {@code ate}{@code Remove}
 */
    public PolicyDecision validateRemove(String packageName, String dependencyType) {
        PolicyDecision packageDecision = validatePackageName(packageName);
        if (!packageDecision.allowed()) {
            return packageDecision;
        }
        return validateDependencyType(dependencyType);
    }

    /**
 * 校验{@code ate}{@code Script}是否有效。
 *
 * @param scriptName 待执行脚本名称
 * @param scriptCommand {@code scriptCommand} 对应的调用参数
 * @return {@code ate}{@code Script}
 */
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

    /**
 * 校验{@code ate}{@code Install}是否有效。
 *
 * @param actionSource 动作来源
 * @return {@code ate}{@code Install}
 */
    public PolicyDecision validateInstall(String actionSource) {
        return PolicyDecision.allowed("install approved by dependency policy", Map.of(
                "actionSource", StrUtil.blankToDefault(actionSource, "manual")
        ));
    }

    /**
 * 规范化依赖类型。
 *
 * @param dependencyType 依赖类型
 * @return 处理后的依赖类型文本
 */
    public String normalizeDependencyType(String dependencyType) {
        if (StrUtil.isBlank(dependencyType)) {
            return DEPENDENCIES;
        }
        String trimmed = dependencyType.trim();
        if (VALID_DEPENDENCY_TYPES.contains(trimmed)) {
            return trimmed;
        }
        throw new ToolInputException("依赖分组仅支持 dependencies 或 devDependencies");
    }

    /** 校验{@code ate}依赖包名称是否有效。 */
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

    /** 校验{@code ate}依赖类型是否有效。 */
    private PolicyDecision validateDependencyType(String dependencyType) {
        try {
            String normalized = normalizeDependencyType(dependencyType);
            return PolicyDecision.allowed("dependency type approved", Map.of("dependencyType", normalized));
        } catch (ToolInputException e) {
            return PolicyDecision.rejected(e.publicMessage());
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
