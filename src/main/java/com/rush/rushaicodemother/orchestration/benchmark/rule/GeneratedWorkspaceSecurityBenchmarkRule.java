package com.rush.rushaicodemother.orchestration.benchmark.rule;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkQualityDimension;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkRuleResult;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkTask;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkValidationRule;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkWorkspaceInspector;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkWorkspaceSnapshot;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Deterministic source and manifest security checks for every generated benchmark workspace. */
@Component
public class GeneratedWorkspaceSecurityBenchmarkRule implements GenerationBenchmarkValidationRule {

    private static final String RULE_ID = "generated_workspace_security";
    private static final int MAX_SCANNED_FILES = 2_000;
    private static final long MAX_SCANNED_FILE_BYTES = 512L * 1_024L;
    private static final Set<String> SOURCE_EXTENSIONS = Set.of(
            "html", "js", "mjs", "cjs", "ts", "jsx", "tsx", "vue", "go", "java"
    );
    private static final Set<String> LIFECYCLE_SCRIPTS = Set.of(
            "preinstall", "install", "postinstall", "prepare", "prepublish", "prepublishonly"
    );
    private static final Pattern REMOTE_RUNTIME_RESOURCE = Pattern.compile(
            "(?is)<(?:script|link)\\b[^>]*(?:src|href)\\s*=\\s*['\"](?:https?:)?//"
    );
    private static final Pattern DYNAMIC_CODE_EXECUTION = Pattern.compile(
            "(?i)(?:\\beval\\s*\\(|\\bnew\\s+Function\\s*\\(|set(?:Timeout|Interval)\\s*\\(\\s*['\"])"
    );
    private static final Pattern UNSAFE_HTML_INJECTION = Pattern.compile(
            "(?i)(?:\\bv-html\\s*=|\\.innerHTML\\s*=|dangerouslySetInnerHTML)"
    );
    private static final Pattern SENSITIVE_FRONTEND_ENV = Pattern.compile(
            "(?i)import\\.meta\\.env\\.[A-Z0-9_]*(?:SECRET|PRIVATE_KEY|API_KEY|ACCESS_TOKEN|PASSWORD)[A-Z0-9_]*"
    );
    private static final Pattern SENSITIVE_PATH_ACCESS = Pattern.compile(
            "(?i)(?:readFile|readFileSync|os\\.ReadFile|Files\\.readString|open)\\s*\\([^)]*(?:\\.\\./|/etc/|\\.env|id_rsa)"
    );
    private static final Pattern DIRECT_SECRET = Pattern.compile(
            "(?i)(?:-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----|AKIA[0-9A-Z]{16}|gh[pousr]_[A-Za-z0-9]{20,}|sk-[A-Za-z0-9_-]{20,})"
    );
    private static final Pattern SECRET_ASSIGNMENT = Pattern.compile(
            "(?i)(?:api[_-]?key|client[_-]?secret|access[_-]?token|auth[_-]?token|password|private[_-]?key)"
                    + "\\s*[:=]\\s*['\"`]([^'\"`\\r\\n]{8,})['\"`]"
    );

    private final GenerationBenchmarkWorkspaceInspector inspector;

    public GeneratedWorkspaceSecurityBenchmarkRule(GenerationBenchmarkWorkspaceInspector inspector) {
        this.inspector = inspector;
    }

    @Override
    public String id() {
        return RULE_ID;
    }

    @Override
    public GenerationBenchmarkQualityDimension dimension() {
        return GenerationBenchmarkQualityDimension.SECURITY;
    }

    @Override
    public boolean supports(GenerationBenchmarkTask task) {
        return task != null;
    }

    @Override
    public GenerationBenchmarkRuleResult evaluate(
            GenerationBenchmarkTask task,
            GenerationWorkspace workspace,
            GenerationBenchmarkWorkspaceSnapshot baseline
    ) {
        GenerationBenchmarkWorkspaceSnapshot current = inspector.capture(workspace.canonicalRootPath());
        List<String> paths = current.fileDigests().keySet().stream().sorted().toList();
        LinkedHashSet<String> violations = new LinkedHashSet<>();
        if (paths.size() > MAX_SCANNED_FILES) {
            violations.add("security_scan_file_count_exceeded");
        }
        for (String relativePath : paths.stream().limit(MAX_SCANNED_FILES).toList()) {
            String normalizedPath = relativePath.toLowerCase(Locale.ROOT);
            if (isSensitiveFile(normalizedPath)) {
                violations.add("sensitive_file_present");
                continue;
            }
            if (normalizedPath.endsWith("package.json")) {
                inspectPackageManifest(workspace.canonicalRootPath(), relativePath, violations);
                continue;
            }
            if (!SOURCE_EXTENSIONS.contains(extension(normalizedPath))) {
                continue;
            }
            Path file = inspector.resolve(workspace.canonicalRootPath(), relativePath);
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            try {
                if (Files.size(file) > MAX_SCANNED_FILE_BYTES) {
                    violations.add("security_scan_file_too_large");
                    continue;
                }
            } catch (Exception failure) {
                throw new IllegalStateException("unable to inspect benchmark security file size", failure);
            }
            inspectSource(inspector.readUtf8(workspace.canonicalRootPath(), relativePath), violations);
        }
        return new GenerationBenchmarkRuleResult(
                RULE_ID,
                dimension(),
                violations.isEmpty(),
                List.copyOf(violations),
                0
        );
    }

    private void inspectPackageManifest(
            Path root,
            String relativePath,
            Set<String> violations
    ) {
        String content = inspector.readUtf8(root, relativePath);
        if (content.length() > MAX_SCANNED_FILE_BYTES) {
            violations.add("security_scan_file_too_large");
            return;
        }
        try {
            JSONObject manifest = JSONUtil.parseObj(content);
            JSONObject scripts = manifest.getJSONObject("scripts");
            if (scripts != null && scripts.keySet().stream()
                    .map(value -> value.toLowerCase(Locale.ROOT))
                    .anyMatch(LIFECYCLE_SCRIPTS::contains)) {
                violations.add("package_lifecycle_script_present");
            }
            inspectDependencies(manifest.getJSONObject("dependencies"), violations);
            inspectDependencies(manifest.getJSONObject("devDependencies"), violations);
            inspectDependencies(manifest.getJSONObject("optionalDependencies"), violations);
        } catch (RuntimeException failure) {
            violations.add("package_manifest_unparseable");
        }
    }

    private void inspectDependencies(JSONObject dependencies, Set<String> violations) {
        if (dependencies == null) {
            return;
        }
        for (String dependency : dependencies.keySet()) {
            String version = dependencies.getStr(dependency, "").trim().toLowerCase(Locale.ROOT);
            if (version.matches(
                    "^(?:https?|git(?:\\+https?|\\+ssh)?|ssh|github|gitlab|bitbucket|file|link):.*"
            ) || version.startsWith("git@")) {
                violations.add("non_registry_dependency_present");
                return;
            }
        }
    }

    private void inspectSource(String source, Set<String> violations) {
        if (source == null || source.isBlank()) {
            return;
        }
        if (REMOTE_RUNTIME_RESOURCE.matcher(source).find()) {
            violations.add("external_runtime_resource_present");
        }
        if (DYNAMIC_CODE_EXECUTION.matcher(source).find()) {
            violations.add("dynamic_code_execution_present");
        }
        if (UNSAFE_HTML_INJECTION.matcher(source).find()) {
            violations.add("unsafe_html_injection_present");
        }
        if (SENSITIVE_FRONTEND_ENV.matcher(source).find()) {
            violations.add("frontend_sensitive_environment_exposure");
        }
        if (SENSITIVE_PATH_ACCESS.matcher(source).find()) {
            violations.add("sensitive_path_access_present");
        }
        if (DIRECT_SECRET.matcher(source).find() || containsHardcodedSecretAssignment(source)) {
            violations.add("hardcoded_secret_present");
        }
    }

    private boolean containsHardcodedSecretAssignment(String source) {
        Matcher matcher = SECRET_ASSIGNMENT.matcher(source);
        while (matcher.find()) {
            String value = matcher.group(1).trim().toLowerCase(Locale.ROOT);
            if (!looksLikePlaceholder(value)) {
                return true;
            }
        }
        return false;
    }

    private boolean looksLikePlaceholder(String value) {
        return value.contains("example")
                || value.contains("placeholder")
                || value.contains("change_me")
                || value.contains("changeme")
                || value.contains("your_")
                || value.contains("your-")
                || value.contains("dummy")
                || value.contains("process.env")
                || value.contains("import.meta.env");
    }

    private boolean isSensitiveFile(String normalizedPath) {
        String fileName = normalizedPath.substring(normalizedPath.lastIndexOf('/') + 1);
        if (fileName.equals(".env") || fileName.startsWith(".env.")) {
            return !fileName.endsWith(".example")
                    && !fileName.endsWith(".sample")
                    && !fileName.endsWith(".template");
        }
        return fileName.equals(".npmrc")
                || fileName.equals(".netrc")
                || fileName.equals(".pypirc")
                || fileName.equals("id_rsa")
                || fileName.equals("id_ed25519")
                || fileName.equals("credentials.json")
                || fileName.matches("service[-_]?account.*\\.json");
    }

    private String extension(String path) {
        int index = path.lastIndexOf('.');
        return index < 0 ? "" : path.substring(index + 1);
    }
}
