package com.rush.rushaicodemother.orchestration.patch;

import com.rush.rushaicodemother.infrastructure.diagnostic.LogExceptionSanitizer;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Rejects frontend patches that introduce undeclared bare package imports. */
@Slf4j
@Component
@RequiredArgsConstructor
public class FrontendPatchImportPolicy {

    private static final Pattern IMPORT_SPECIFIER_PATTERN = Pattern.compile(
            "(?:import\\s+(?:[^'\";]+\\s+from\\s+)?|export\\s+[^'\";]+\\s+from\\s+|import\\s*\\()(['\"])([^'\"]+)\\1"
    );
    private static final Set<String> BUILTIN_ALLOWED_BARE_IMPORTS = Set.of(
            "vue", "vue-router", "pinia", "@vueuse/core"
    );

    private final PatchWorkspaceFileService workspaceFileService;

    public String validate(Path projectRoot,
                           String action,
                           PatchOperation operation,
                           String normalizedPath,
                           PatchWorkspaceTarget target) {
        if (!isFrontendSourceFile(normalizedPath) || PatchOperation.ACTION_DELETE.equals(action)) {
            return "";
        }
        try {
            ImportContentChange contentChange = previewContentChange(action, operation, target);
            if (contentChange == null || contentChange.afterContent() == null) {
                return "";
            }
            Set<String> allowedPackages = readDeclaredPackages(projectRoot);
            Set<String> newPackages = extractBareImportPackages(contentChange.afterContent());
            newPackages.removeAll(extractBareImportPackages(contentChange.beforeContent()));
            for (String packageName : newPackages) {
                if (!allowedPackages.contains(packageName) && !BUILTIN_ALLOWED_BARE_IMPORTS.contains(packageName)) {
                    return "undeclared_bare_import:" + packageName;
                }
            }
            return "";
        } catch (PatchWorkspaceException exception) {
            return exception.reason();
        } catch (IOException exception) {
            log.debug("Frontend import validation failed for {}: {}", normalizedPath, LogExceptionSanitizer.sanitizeMessage(exception));
            return "dependency_validation_failed";
        }
    }

    private ImportContentChange previewContentChange(String action,
                                                     PatchOperation operation,
                                                     PatchWorkspaceTarget target) throws IOException {
        return switch (action) {
            case PatchOperation.ACTION_ADD -> new ImportContentChange("", operation.content());
            case PatchOperation.ACTION_MODIFY -> new ImportContentChange(
                    workspaceFileService.readUtf8(target), operation.content());
            case PatchOperation.ACTION_REPLACE -> {
                String original = workspaceFileService.readUtf8(target);
                yield new ImportContentChange(original,
                        original.replace(operation.oldContent(), operation.newContent()));
            }
            case PatchOperation.ACTION_INSERT_BEFORE_MARKER, PatchOperation.ACTION_INSERT_AFTER_MARKER -> {
                String original = workspaceFileService.readUtf8(target);
                String marker = operation.oldContent();
                String replacement = PatchOperation.ACTION_INSERT_BEFORE_MARKER.equals(action)
                        ? operation.newContent() + System.lineSeparator() + marker
                        : marker + System.lineSeparator() + operation.newContent();
                yield new ImportContentChange(original, original.replace(marker, replacement));
            }
            default -> null;
        };
    }

    private Set<String> readDeclaredPackages(Path projectRoot) throws IOException {
        PatchWorkspaceTarget packageJsonTarget = workspaceFileService.resolve(projectRoot, "package.json");
        if (!workspaceFileService.exists(packageJsonTarget)
                || !workspaceFileService.isRegularFile(packageJsonTarget)) {
            return Set.of();
        }
        try {
            JSONObject packageJson = JSONUtil.parseObj(workspaceFileService.readUtf8(packageJsonTarget));
            Set<String> packages = new LinkedHashSet<>();
            addPackageSection(packages, packageJson.getJSONObject("dependencies"));
            addPackageSection(packages, packageJson.getJSONObject("devDependencies"));
            addPackageSection(packages, packageJson.getJSONObject("peerDependencies"));
            addPackageSection(packages, packageJson.getJSONObject("optionalDependencies"));
            return packages;
        } catch (RuntimeException exception) {
            log.debug("Unable to parse package.json dependencies: {}", LogExceptionSanitizer.sanitizeMessage(exception));
            return Set.of();
        }
    }

    private Set<String> extractBareImportPackages(String content) {
        Set<String> packages = new LinkedHashSet<>();
        if (StrUtil.isBlank(content)) {
            return packages;
        }
        Matcher matcher = IMPORT_SPECIFIER_PATTERN.matcher(content);
        while (matcher.find()) {
            String packageName = barePackageName(matcher.group(2));
            if (StrUtil.isNotBlank(packageName)) {
                packages.add(packageName);
            }
        }
        return packages;
    }

    private void addPackageSection(Set<String> packages, JSONObject section) {
        if (section != null) {
            packages.addAll(section.keySet());
        }
    }

    private String barePackageName(String specifier) {
        if (StrUtil.isBlank(specifier)
                || specifier.startsWith(".")
                || specifier.startsWith("/")
                || specifier.startsWith("@/")
                || specifier.startsWith("~")) {
            return "";
        }
        if (specifier.startsWith("@")) {
            String[] parts = specifier.split("/");
            return parts.length >= 2 ? parts[0] + "/" + parts[1] : specifier;
        }
        int slashIndex = specifier.indexOf('/');
        return slashIndex < 0 ? specifier : specifier.substring(0, slashIndex);
    }

    private boolean isFrontendSourceFile(String path) {
        return path.endsWith(".vue")
                || path.endsWith(".js")
                || path.endsWith(".mjs")
                || path.endsWith(".ts")
                || path.endsWith(".jsx")
                || path.endsWith(".tsx");
    }

    private record ImportContentChange(String beforeContent, String afterContent) {
    }
}
