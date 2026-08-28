package com.rush.rushaicodemother.orchestration.benchmark.rule;

import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkQualityDimension;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkRuleResult;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkTask;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkValidationRule;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkWorkspaceInspector;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkWorkspaceSnapshot;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import com.rush.rushaicodemother.security.workspace.GeneratedSqlSafetyPolicy;
import com.rush.rushaicodemother.security.workspace.GeneratedWorkspaceTrustPolicy;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 对每个生成的基准工作区执行共享信任策略与确定性源码安全检查。 */
@Component
public class GeneratedWorkspaceSecurityBenchmarkRule implements GenerationBenchmarkValidationRule {

    private static final String RULE_ID = "generated_workspace_security";
    private static final int MAX_SCANNED_FILES = 2_000;
    private static final long MAX_SCANNED_FILE_BYTES = 512L * 1_024L;
    private static final Set<String> SOURCE_EXTENSIONS = Set.of(
            "html", "js", "mjs", "cjs", "ts", "jsx", "tsx", "vue", "go", "java"
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
    private final GeneratedWorkspaceTrustPolicy workspaceTrustPolicy;
    private final GeneratedSqlSafetyPolicy sqlSafetyPolicy;

    public GeneratedWorkspaceSecurityBenchmarkRule(
            GenerationBenchmarkWorkspaceInspector inspector,
            GeneratedWorkspaceTrustPolicy workspaceTrustPolicy,
            GeneratedSqlSafetyPolicy sqlSafetyPolicy
    ) {
        this.inspector = inspector;
        this.workspaceTrustPolicy = workspaceTrustPolicy;
        this.sqlSafetyPolicy = sqlSafetyPolicy;
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
        GenerationBenchmarkWorkspaceSnapshot current = inspector.capture(workspace);
        List<String> paths = current.fileDigests().keySet().stream().sorted().toList();
        LinkedHashSet<String> violations = new LinkedHashSet<>();
        Map<String, String> baselineFiles = baseline == null
                ? Map.of()
                : baseline.fileDigests();
        if (paths.size() > MAX_SCANNED_FILES) {
            violations.add("security_scan_file_count_exceeded");
        }
        // 按既定顺序逐项处理，并在达到资源或状态边界时提前结束。
        for (String relativePath : paths.stream().limit(MAX_SCANNED_FILES).toList()) {
            inspectWorkspaceTrust(
                    workspace.canonicalRootPath(),
                    relativePath,
                    current.fileDigests().get(relativePath),
                    baselineFiles,
                    violations
            );
            String normalizedPath = relativePath.toLowerCase(Locale.ROOT);
            if (isSensitiveFile(normalizedPath)) {
                // 既有仓库可能含待审计的敏感文件；只把生成链新增或改写敏感文件记为本次违规。
                if (!Objects.equals(current.fileDigests().get(relativePath),
                        baselineFiles.get(relativePath))) {
                    violations.add("sensitive_file_present");
                }
                continue;
            }
            String fileExtension = extension(normalizedPath);
            boolean sourceFile = SOURCE_EXTENSIONS.contains(fileExtension);
            boolean sqlFile = "sql".equals(fileExtension);
            if (!sourceFile && !sqlFile) {
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
            String content = inspector.readUtf8(workspace.canonicalRootPath(), relativePath);
            if (sqlFile) {
                violations.addAll(sqlSafetyPolicy.validateAll(content));
            } else {
                inspectSource(content, violations);
            }
        }
        inspectDeletedWorkspaceControls(current.fileDigests(), baselineFiles, violations);
        return new GenerationBenchmarkRuleResult(
                RULE_ID,
                dimension(),
                violations.isEmpty(),
                List.copyOf(violations),
                0
        );
    }

    /**
     * Benchmark 只评估相对基线由生成链路新增或修改的文件，并复用生产写入策略。
     * 这样既不会误判模板提供的可信 lockfile，也不会形成第二套清单解析规则。
     */
    private void inspectWorkspaceTrust(
            Path root,
            String relativePath,
            String currentDigest,
            Map<String, String> baselineFiles,
            Set<String> violations
    ) {
        if (!workspaceTrustPolicy.appliesTo(relativePath)
                || baselineFiles.containsKey(relativePath)
                && Objects.equals(currentDigest, baselineFiles.get(relativePath))) {
            return;
        }
        Path file = inspector.resolve(root, relativePath);
        try {
            if (Files.size(file) > MAX_SCANNED_FILE_BYTES) {
                violations.add("security_scan_file_too_large");
                return;
            }
        } catch (Exception failure) {
            throw new IllegalStateException("unable to inspect benchmark trust file size", failure);
        }
        workspaceTrustPolicy.validateAll(relativePath, inspector.readUtf8(root, relativePath))
                .forEach(reason -> recordWorkspaceTrustViolation(reason, violations));
    }

    private void inspectDeletedWorkspaceControls(
            Map<String, String> currentFiles,
            Map<String, String> baselineFiles,
            Set<String> violations
    ) {
        baselineFiles.keySet().stream()
                .filter(workspaceTrustPolicy::appliesTo)
                .filter(relativePath -> !currentFiles.containsKey(relativePath))
                .sorted()
                .map(workspaceTrustPolicy::validateDeletion)
                .forEach(reason -> recordWorkspaceTrustViolation(reason, violations));
    }

    /** 保留历史聚合维度，同时把生产策略的精确拒绝原因写入评测证据。 */
    private void recordWorkspaceTrustViolation(String reason, Set<String> violations) {
        if (reason == null || reason.isBlank()) {
            return;
        }
        violations.add(reason);
        if (reason.startsWith("executable_manifest_forbidden_lifecycle:")) {
            violations.add("package_lifecycle_script_present");
        } else if (reason.startsWith("executable_manifest_forbidden_dependency_source:")) {
            violations.add("non_registry_dependency_present");
        } else if (reason.equals("executable_manifest_invalid_json")
                || reason.equals("executable_manifest_not_object")) {
            violations.add("package_manifest_unparseable");
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

    /** 判断{@code Sensitive}文件是否满足约束。 */
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
