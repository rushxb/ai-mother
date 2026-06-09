package com.rush.rushaicodemother.orchestration.patch;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.rush.rushaicodemother.monitor.GenerationOrchestrationMetricsCollector;
import com.rush.rushaicodemother.orchestration.artifact.ChangePlan;
import com.rush.rushaicodemother.orchestration.artifact.GenerationArtifact;
import com.rush.rushaicodemother.orchestration.artifact.PatchApplyResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 受 ChangePlan 约束的本地文件级补丁执行器。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GenerationPatchApplyService {

    private static final Set<String> SUPPORTED_ACTIONS = Set.of(
            PatchOperation.ACTION_ADD,
            PatchOperation.ACTION_MODIFY,
            PatchOperation.ACTION_REPLACE,
            PatchOperation.ACTION_DELETE,
            PatchOperation.ACTION_INSERT_BEFORE_MARKER,
            PatchOperation.ACTION_INSERT_AFTER_MARKER,
            PatchOperation.ACTION_GO_ADD_IMPORT,
            PatchOperation.ACTION_GO_APPEND_TO_FUNCTION,
            PatchOperation.ACTION_GO_ADD_STRUCT_FIELDS,
            PatchOperation.ACTION_APPEND_SQL_MIGRATION
    );
    private static final Pattern IMPORT_SPECIFIER_PATTERN = Pattern.compile(
            "(?:import\\s+(?:[^'\";]+\\s+from\\s+)?|export\\s+[^'\";]+\\s+from\\s+|import\\s*\\()(['\"])([^'\"]+)\\1"
    );
    private static final Set<String> BUILTIN_ALLOWED_BARE_IMPORTS = Set.of(
            "vue", "vue-router", "pinia", "@vueuse/core"
    );

    private final GenerationOrchestrationMetricsCollector metricsCollector;

    public PatchApplyResult apply(Long appId,
                                  String taskId,
                                  Path projectRoot,
                                  GenerationArtifact changePlanArtifact,
                                  List<PatchOperation> operations) {
        if (changePlanArtifact == null || changePlanArtifact.payload() == null || changePlanArtifact.payload().isEmpty()) {
            return record(PatchApplyResult.skipped(appId, taskId, normalizeRoot(projectRoot) == null ? "" : normalizeRoot(projectRoot).toString(), "change_plan_missing"));
        }
        return apply(appId, taskId, projectRoot, ChangePlan.fromPayload(payload(changePlanArtifact)), operations);
    }

    public PatchApplyResult apply(Long appId,
                                  String taskId,
                                  Path projectRoot,
                                  ChangePlan changePlan,
                                  List<PatchOperation> operations) {
        Path normalizedRoot = normalizeRoot(projectRoot);
        String projectPath = pathToString(normalizedRoot);
        if (normalizedRoot == null || !Files.isDirectory(normalizedRoot)) {
            return record(PatchApplyResult.skipped(appId, taskId, projectPath, "project_root_missing"));
        }
        if (changePlan == null) {
            return record(PatchApplyResult.skipped(appId, taskId, projectPath, "change_plan_missing"));
        }
        if ("project_bootstrap".equals(changePlan.changeScope())) {
            return record(PatchApplyResult.skipped(appId, taskId, projectPath, "project_bootstrap_not_patch_first"));
        }
        if (operations == null || operations.isEmpty()) {
            return record(PatchApplyResult.skipped(appId, taskId, projectPath, "patch_operations_empty"));
        }
        ValidationResult validationResult = validate(normalizedRoot, changePlan, operations);
        if (!validationResult.rejectedOperations().isEmpty()) {
            return record(PatchApplyResult.rejected(
                    appId,
                    taskId,
                    projectPath,
                    operations.size(),
                    validationResult.rejectedOperations(),
                    "patch_operation_validation_failed"
            ));
        }
        try {
            List<String> appliedFiles = applyValidatedOperations(validationResult.validOperations());
            return record(PatchApplyResult.applied(appId, taskId, projectPath, operations.size(), appliedFiles));
        } catch (Exception e) {
            log.warn("本地补丁执行失败，appId: {}, taskId: {}", appId, taskId, e);
            return record(PatchApplyResult.rejected(
                    appId,
                    taskId,
                    projectPath,
                    operations.size(),
                    List.of("executor:" + e.getMessage()),
                    "patch_apply_failed"
            ));
        }
    }

    public PatchApplyResult applyWithoutChangePlan(Long appId,
                                                   String taskId,
                                                   Path projectRoot,
                                                   List<PatchOperation> operations,
                                                   String reason) {
        Path normalizedRoot = normalizeRoot(projectRoot);
        String projectPath = pathToString(normalizedRoot);
        if (normalizedRoot == null || !Files.isDirectory(normalizedRoot)) {
            return record(PatchApplyResult.skipped(appId, taskId, projectPath, "project_root_missing"));
        }
        if (operations == null || operations.isEmpty()) {
            return record(PatchApplyResult.skipped(appId, taskId, projectPath, "patch_operations_empty"));
        }
        ValidationResult validationResult = validate(normalizedRoot, null, operations);
        if (!validationResult.rejectedOperations().isEmpty()) {
            return record(PatchApplyResult.rejected(
                    appId,
                    taskId,
                    projectPath,
                    operations.size(),
                    validationResult.rejectedOperations(),
                    "patch_operation_validation_failed"
            ));
        }
        try {
            List<String> appliedFiles = applyValidatedOperations(validationResult.validOperations());
            return record(PatchApplyResult.applied(appId, taskId, projectPath, operations.size(), appliedFiles));
        } catch (Exception e) {
            log.warn("无计划补丁执行失败，appId: {}, taskId: {}, reason: {}", appId, taskId, reason, e);
            return record(PatchApplyResult.rejected(appId, taskId, projectPath, operations.size(),
                    List.of("executor:" + e.getMessage()), "patch_apply_failed"));
        }
    }

    public String renderText(PatchApplyResult result) {
        if (result == null) {
            return "补丁执行结果不可用";
        }
        if ("applied".equals(result.status())) {
            return "补丁执行成功，已落盘 " + result.appliedOperationCount() + " 个操作。";
        }
        if ("rejected".equals(result.status())) {
            return "补丁执行已拒绝，原因: " + result.reason() + "，拒绝操作 " + result.rejectedOperationCount() + " 个。";
        }
        return "补丁执行已跳过: " + result.reason();
    }

    private ValidationResult validate(Path projectRoot, ChangePlan changePlan, List<PatchOperation> operations) {
        List<ValidatedOperation> validOperations = new ArrayList<>();
        List<String> rejectedOperations = new ArrayList<>();
        Set<String> seenPaths = new LinkedHashSet<>();
        for (PatchOperation operation : operations) {
            String action = operation == null ? "" : operation.action();
            String normalizedPath = normalizePath(operation == null ? "" : operation.relativePath());
            String operationLabel = StrUtil.blankToDefault(action, "unknown") + ":" + StrUtil.blankToDefault(normalizedPath, "");
            if (!SUPPORTED_ACTIONS.contains(action)) {
                rejectedOperations.add(operationLabel + ":unsupported_action");
                continue;
            }
            if (StrUtil.isBlank(normalizedPath)) {
                rejectedOperations.add(operationLabel + ":invalid_path");
                continue;
            }
            if (changePlan != null && !seenPaths.add(normalizedPath)) {
                rejectedOperations.add(operationLabel + ":duplicate_operation_path");
                continue;
            }
            if (changePlan != null && !isPlanned(changePlan, action, normalizedPath)) {
                rejectedOperations.add(operationLabel + ":outside_change_plan");
                continue;
            }
            Path targetPath = projectRoot.resolve(normalizedPath).toAbsolutePath().normalize();
            if (!targetPath.startsWith(projectRoot)) {
                rejectedOperations.add(operationLabel + ":path_outside_project");
                continue;
            }
            String blocker = validateTarget(action, operation, targetPath);
            if (StrUtil.isNotBlank(blocker)) {
                rejectedOperations.add(operationLabel + ":" + blocker);
                continue;
            }
            String dependencyBlocker = validateNoUnknownBareImports(projectRoot, action, operation, normalizedPath, targetPath);
            if (StrUtil.isNotBlank(dependencyBlocker)) {
                rejectedOperations.add(operationLabel + ":" + dependencyBlocker);
                continue;
            }
            validOperations.add(new ValidatedOperation(action, normalizedPath, targetPath, operation));
        }
        return new ValidationResult(validOperations, rejectedOperations);
    }

    private List<String> applyValidatedOperations(List<ValidatedOperation> operations) throws IOException {
        List<String> appliedFiles = new ArrayList<>();
        for (ValidatedOperation operation : operations) {
            PatchOperation patchOperation = operation.operation();
            Path parent = operation.targetPath().getParent();
            if (parent != null && !PatchOperation.ACTION_DELETE.equals(operation.action())) {
                Files.createDirectories(parent);
            }
            switch (operation.action()) {
                case PatchOperation.ACTION_ADD -> Files.writeString(
                        operation.targetPath(),
                        patchOperation.content(),
                        StandardCharsets.UTF_8,
                        StandardOpenOption.WRITE,
                        StandardOpenOption.CREATE_NEW
                );
                case PatchOperation.ACTION_MODIFY -> Files.writeString(
                        operation.targetPath(),
                        patchOperation.content(),
                        StandardCharsets.UTF_8,
                        StandardOpenOption.WRITE,
                        StandardOpenOption.TRUNCATE_EXISTING
                );
                case PatchOperation.ACTION_REPLACE -> {
                    String originalContent = Files.readString(operation.targetPath(), StandardCharsets.UTF_8);
                    String modifiedContent = originalContent.replace(patchOperation.oldContent(), patchOperation.newContent());
                    Files.writeString(
                            operation.targetPath(),
                            modifiedContent,
                            StandardCharsets.UTF_8,
                            StandardOpenOption.WRITE,
                            StandardOpenOption.TRUNCATE_EXISTING
                    );
                }
                case PatchOperation.ACTION_INSERT_BEFORE_MARKER, PatchOperation.ACTION_INSERT_AFTER_MARKER -> {
                    String originalContent = Files.readString(operation.targetPath(), StandardCharsets.UTF_8);
                    String marker = patchOperation.oldContent();
                    String snippet = patchOperation.newContent();
                    String replacement = PatchOperation.ACTION_INSERT_BEFORE_MARKER.equals(operation.action())
                            ? snippet + System.lineSeparator() + marker
                            : marker + System.lineSeparator() + snippet;
                    String modifiedContent = originalContent.replace(marker, replacement);
                    Files.writeString(
                            operation.targetPath(),
                            modifiedContent,
                            StandardCharsets.UTF_8,
                            StandardOpenOption.WRITE,
                            StandardOpenOption.TRUNCATE_EXISTING
                    );
                }
                case PatchOperation.ACTION_GO_ADD_IMPORT -> writeStructuredGoImport(operation.targetPath(), patchOperation.newContent());
                case PatchOperation.ACTION_GO_APPEND_TO_FUNCTION -> writeGoFunctionAppend(
                        operation.targetPath(), patchOperation.oldContent(), patchOperation.newContent());
                case PatchOperation.ACTION_GO_ADD_STRUCT_FIELDS -> writeGoStructFields(
                        operation.targetPath(), patchOperation.oldContent(), patchOperation.newContent());
                case PatchOperation.ACTION_APPEND_SQL_MIGRATION -> writeSqlMigrationAppend(
                        operation.targetPath(), patchOperation.newContent());
                case PatchOperation.ACTION_DELETE -> Files.delete(operation.targetPath());
                default -> throw new IllegalStateException("Unsupported patch action: " + operation.action());
            }
            appliedFiles.add(operation.action() + ":" + operation.relativePath());
        }
        return appliedFiles;
    }

    private String validateTarget(String action, PatchOperation operation, Path targetPath) {
        if (PatchOperation.ACTION_ADD.equals(action)) {
            if (operation.content() == null) {
                return "content_missing";
            }
            return Files.exists(targetPath) ? "add_target_already_exists" : "";
        }
        if (PatchOperation.ACTION_MODIFY.equals(action)) {
            if (operation.content() == null) {
                return "content_missing";
            }
            return Files.isRegularFile(targetPath) ? "" : "modify_target_missing";
        }
        if (PatchOperation.ACTION_REPLACE.equals(action)) {
            if (StrUtil.isBlank(operation.oldContent()) || operation.newContent() == null) {
                return "replace_content_missing";
            }
            if (!Files.isRegularFile(targetPath)) {
                return "replace_target_missing";
            }
            try {
                String originalContent = Files.readString(targetPath, StandardCharsets.UTF_8);
                return originalContent.contains(operation.oldContent()) ? "" : "old_content_not_found";
            } catch (IOException e) {
                return "read_target_failed";
            }
        }
        if (PatchOperation.ACTION_INSERT_BEFORE_MARKER.equals(action)
                || PatchOperation.ACTION_INSERT_AFTER_MARKER.equals(action)) {
            if (StrUtil.isBlank(operation.oldContent()) || operation.newContent() == null) {
                return "marker_content_missing";
            }
            if (!Files.isRegularFile(targetPath)) {
                return "marker_target_missing";
            }
            try {
                String originalContent = Files.readString(targetPath, StandardCharsets.UTF_8);
                return originalContent.contains(operation.oldContent()) ? "" : "marker_not_found";
            } catch (IOException e) {
                return "read_target_failed";
            }
        }
        if (PatchOperation.ACTION_GO_ADD_IMPORT.equals(action)) {
            return validateStructuredTarget(targetPath, operation.newContent(), ".go", "go_import_content_missing");
        }
        if (PatchOperation.ACTION_GO_APPEND_TO_FUNCTION.equals(action)
                || PatchOperation.ACTION_GO_ADD_STRUCT_FIELDS.equals(action)) {
            if (StrUtil.isBlank(operation.oldContent()) || StrUtil.isBlank(operation.newContent())) {
                return "go_structured_content_missing";
            }
            return validateStructuredTarget(targetPath, operation.newContent(), ".go", "go_structured_target_missing");
        }
        if (PatchOperation.ACTION_APPEND_SQL_MIGRATION.equals(action)) {
            String blocker = validateStructuredTarget(targetPath, operation.newContent(), ".sql", "sql_migration_content_missing");
            if (StrUtil.isNotBlank(blocker)) {
                return blocker;
            }
            return containsDangerousSql(operation.newContent()) ? "dangerous_sql_migration" : "";
        }
        if (PatchOperation.ACTION_DELETE.equals(action)) {
            return Files.isRegularFile(targetPath) ? "" : "delete_target_missing";
        }
        return "unsupported_action";
    }

    private boolean isPlanned(ChangePlan changePlan, String action, String normalizedPath) {
        return switch (action) {
            case PatchOperation.ACTION_ADD -> changePlan.addFiles().contains(normalizedPath);
            case PatchOperation.ACTION_MODIFY,
                 PatchOperation.ACTION_REPLACE,
                 PatchOperation.ACTION_INSERT_BEFORE_MARKER,
                 PatchOperation.ACTION_INSERT_AFTER_MARKER,
                 PatchOperation.ACTION_GO_ADD_IMPORT,
                 PatchOperation.ACTION_GO_APPEND_TO_FUNCTION,
                 PatchOperation.ACTION_GO_ADD_STRUCT_FIELDS,
                 PatchOperation.ACTION_APPEND_SQL_MIGRATION ->
                    changePlan.modifyFiles().contains(normalizedPath);
            case PatchOperation.ACTION_DELETE -> changePlan.deleteFiles().contains(normalizedPath);
            default -> false;
        };
    }

    private void writeStructuredGoImport(Path targetPath, String importPath) throws IOException {
        String normalizedImport = StrUtil.blankToDefault(importPath, "").trim();
        String quotedImport = "\"" + normalizedImport.replace("\"", "") + "\"";
        String content = Files.readString(targetPath, StandardCharsets.UTF_8);
        if (content.contains(quotedImport)) {
            return;
        }
        if (content.contains("import (\n")) {
            content = content.replace("import (\n", "import (\n\t" + quotedImport + "\n");
        } else if (content.matches("(?s).*import\\s+\"[^\"]+\".*")) {
            content = content.replaceFirst("import\\s+\"([^\"]+)\"", "import (\n\t\"$1\"\n\t" + quotedImport + "\n)");
        } else {
            int packageLineEnd = content.indexOf('\n');
            if (packageLineEnd < 0) {
                throw new IOException("invalid_go_file_missing_package_line");
            }
            content = content.substring(0, packageLineEnd + 1)
                    + "\nimport " + quotedImport + "\n"
                    + content.substring(packageLineEnd + 1);
        }
        Files.writeString(targetPath, content, StandardCharsets.UTF_8, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    private void writeGoFunctionAppend(Path targetPath, String functionName, String snippet) throws IOException {
        String content = Files.readString(targetPath, StandardCharsets.UTF_8);
        int funcIndex = content.indexOf("func " + functionName);
        if (funcIndex < 0) {
            funcIndex = content.indexOf("func (" + functionName);
        }
        if (funcIndex < 0) {
            throw new IOException("go_function_not_found:" + functionName);
        }
        int bodyStart = content.indexOf('{', funcIndex);
        int bodyEnd = findMatchingBrace(content, bodyStart);
        if (bodyStart < 0 || bodyEnd < 0) {
            throw new IOException("go_function_body_not_found:" + functionName);
        }
        String normalizedSnippet = ensureTrailingNewline(snippet);
        String updated = content.substring(0, bodyEnd)
                + "\n" + normalizedSnippet
                + content.substring(bodyEnd);
        Files.writeString(targetPath, updated, StandardCharsets.UTF_8, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    private void writeGoStructFields(Path targetPath, String structName, String fields) throws IOException {
        String content = Files.readString(targetPath, StandardCharsets.UTF_8);
        int structIndex = content.indexOf("type " + structName + " struct");
        if (structIndex < 0) {
            throw new IOException("go_struct_not_found:" + structName);
        }
        int bodyStart = content.indexOf('{', structIndex);
        int bodyEnd = findMatchingBrace(content, bodyStart);
        if (bodyStart < 0 || bodyEnd < 0) {
            throw new IOException("go_struct_body_not_found:" + structName);
        }
        String normalizedFields = ensureTrailingNewline(fields);
        String updated = content.substring(0, bodyEnd)
                + normalizedFields
                + content.substring(bodyEnd);
        Files.writeString(targetPath, updated, StandardCharsets.UTF_8, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    private void writeSqlMigrationAppend(Path targetPath, String migrationSql) throws IOException {
        String content = Files.readString(targetPath, StandardCharsets.UTF_8);
        String normalizedMigration = ensureTrailingNewline(migrationSql);
        if (content.contains(normalizedMigration.trim())) {
            return;
        }
        String updated = ensureTrailingNewline(content)
                + "\n-- @AI_GENERATED_MIGRATION\n"
                + normalizedMigration;
        Files.writeString(targetPath, updated, StandardCharsets.UTF_8, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    private int findMatchingBrace(String content, int openBraceIndex) {
        if (openBraceIndex < 0) {
            return -1;
        }
        int depth = 0;
        for (int i = openBraceIndex; i < content.length(); i++) {
            char c = content.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private String validateStructuredTarget(Path targetPath, String content, String extension, String missingReason) {
        if (StrUtil.isBlank(content)) {
            return missingReason;
        }
        if (!Files.isRegularFile(targetPath)) {
            return "structured_target_missing";
        }
        return targetPath.getFileName().toString().endsWith(extension) ? "" : "structured_target_extension_mismatch";
    }

    private boolean containsDangerousSql(String sql) {
        String normalized = StrUtil.blankToDefault(sql, "").toLowerCase();
        return normalized.contains("drop table")
                || normalized.contains("drop database")
                || normalized.contains("truncate table")
                || normalized.contains("delete from users")
                || normalized.contains("pragma writable_schema");
    }

    private String validateNoUnknownBareImports(Path projectRoot,
                                                String action,
                                                PatchOperation operation,
                                                String normalizedPath,
                                                Path targetPath) {
        if (!isFrontendSourceFile(normalizedPath) || PatchOperation.ACTION_DELETE.equals(action)) {
            return "";
        }
        try {
            ImportContentChange contentChange = previewImportContentChange(action, operation, targetPath);
            if (contentChange == null || contentChange.afterContent() == null) {
                return "";
            }
            Set<String> allowedPackages = readDeclaredPackages(projectRoot);
            Set<String> beforePackages = extractBareImportPackages(contentChange.beforeContent());
            Set<String> afterPackages = extractBareImportPackages(contentChange.afterContent());
            afterPackages.removeAll(beforePackages);
            for (String packageName : afterPackages) {
                if (!allowedPackages.contains(packageName) && !BUILTIN_ALLOWED_BARE_IMPORTS.contains(packageName)) {
                    return "undeclared_bare_import:" + packageName;
                }
            }
        } catch (Exception e) {
            log.debug("校验前端裸包导入失败: {}, error: {}", normalizedPath, e.getMessage());
        }
        return "";
    }

    private ImportContentChange previewImportContentChange(String action, PatchOperation operation, Path targetPath) throws IOException {
        return switch (action) {
            case PatchOperation.ACTION_ADD -> new ImportContentChange("", operation.content());
            case PatchOperation.ACTION_MODIFY -> {
                String beforeContent = Files.isRegularFile(targetPath)
                        ? Files.readString(targetPath, StandardCharsets.UTF_8)
                        : "";
                yield new ImportContentChange(beforeContent, operation.content());
            }
            case PatchOperation.ACTION_REPLACE -> {
                String originalContent = Files.readString(targetPath, StandardCharsets.UTF_8);
                yield new ImportContentChange(
                        originalContent,
                        originalContent.replace(operation.oldContent(), operation.newContent())
                );
            }
            case PatchOperation.ACTION_INSERT_BEFORE_MARKER, PatchOperation.ACTION_INSERT_AFTER_MARKER -> {
                String originalContent = Files.readString(targetPath, StandardCharsets.UTF_8);
                String marker = operation.oldContent();
                String snippet = operation.newContent();
                String replacement = PatchOperation.ACTION_INSERT_BEFORE_MARKER.equals(action)
                        ? snippet + System.lineSeparator() + marker
                        : marker + System.lineSeparator() + snippet;
                yield new ImportContentChange(originalContent, originalContent.replace(marker, replacement));
            }
            default -> null;
        };
    }

    private Set<String> extractBareImportPackages(String content) {
        if (StrUtil.isBlank(content)) {
            return new LinkedHashSet<>();
        }
        Set<String> packages = new LinkedHashSet<>();
        Matcher matcher = IMPORT_SPECIFIER_PATTERN.matcher(content);
        while (matcher.find()) {
            String packageName = barePackageName(matcher.group(2));
            if (StrUtil.isNotBlank(packageName)) {
                packages.add(packageName);
            }
        }
        return packages;
    }

    private Set<String> readDeclaredPackages(Path projectRoot) {
        Path packageJsonPath = projectRoot.resolve("package.json");
        if (!Files.isRegularFile(packageJsonPath)) {
            return Set.of();
        }
        try {
            JSONObject packageJson = JSONUtil.parseObj(Files.readString(packageJsonPath, StandardCharsets.UTF_8));
            Set<String> packages = new LinkedHashSet<>();
            addPackageSection(packages, packageJson.getJSONObject("dependencies"));
            addPackageSection(packages, packageJson.getJSONObject("devDependencies"));
            addPackageSection(packages, packageJson.getJSONObject("peerDependencies"));
            addPackageSection(packages, packageJson.getJSONObject("optionalDependencies"));
            return packages;
        } catch (Exception e) {
            log.debug("读取 package.json 依赖失败: {}", e.getMessage());
            return Set.of();
        }
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

    private boolean isFrontendSourceFile(String normalizedPath) {
        return normalizedPath.endsWith(".vue")
                || normalizedPath.endsWith(".js")
                || normalizedPath.endsWith(".mjs")
                || normalizedPath.endsWith(".ts")
                || normalizedPath.endsWith(".jsx")
                || normalizedPath.endsWith(".tsx");
    }

    private String ensureTrailingNewline(String value) {
        String normalized = StrUtil.blankToDefault(value, "").stripTrailing();
        return normalized.isEmpty() ? "" : normalized + System.lineSeparator();
    }

    private String normalizePath(String path) {
        List<String> normalized = ChangePlan.normalizeFilePaths(List.of(StrUtil.blankToDefault(path, "")));
        return normalized.isEmpty() ? "" : normalized.getFirst();
    }

    private Path normalizeRoot(Path projectRoot) {
        return projectRoot == null ? null : projectRoot.toAbsolutePath().normalize();
    }

    private String pathToString(Path path) {
        return path == null ? "" : path.toString();
    }

    private Map<String, Object> payload(GenerationArtifact artifact) {
        return artifact == null || artifact.payload() == null ? Map.of() : artifact.payload();
    }

    private PatchApplyResult record(PatchApplyResult result) {
        if (metricsCollector != null) {
            metricsCollector.recordPatchApply(result.provider(), result.status(), result.reason());
        }
        return result;
    }

    private record ValidatedOperation(String action, String relativePath, Path targetPath, PatchOperation operation) {
    }

    private record ValidationResult(List<ValidatedOperation> validOperations, List<String> rejectedOperations) {
    }

    private record ImportContentChange(String beforeContent, String afterContent) {
    }
}
