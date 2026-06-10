package com.rush.rushaicodemother.orchestration.create;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.rush.rushaicodemother.orchestration.codegraph.StructuredSyntaxValidationService;
import com.rush.rushaicodemother.orchestration.patch.PatchOperation;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class CreatePreWriteValidationService {

    private static final Pattern PRIVATE_ADDRESS_PATTERN = Pattern.compile(
            "(?i)(https?://)?(10\\.\\d+\\.\\d+\\.\\d+|172\\.(1[6-9]|2\\d|3[0-1])\\.\\d+\\.\\d+|192\\.168\\.\\d+\\.\\d+)"
    );
    private static final Pattern SECRET_PATTERN = Pattern.compile(
            "(?i)(api[_-]?key|apikey|secret|token|password)\\s*[:=]\\s*['\\\"][A-Za-z0-9_\\-]{16,}['\\\"]"
    );
    private static final Pattern GO_IMPORT_PATH_PATTERN = Pattern.compile(
            "^[A-Za-z0-9_.\\-/]+$"
    );
    private static final Pattern GO_PACKAGE_PATTERN = Pattern.compile(
            "(?m)^package\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*$"
    );
    private static final Pattern GO_MODULE_PACKAGE_PATH_PATTERN = Pattern.compile(
            "(?:^|/)internal/modules/([A-Za-z_][A-Za-z0-9_]*)/[^/]+\\.go$"
    );
    private static final Set<String> DANGEROUS_SCRIPT_TOKENS = Set.of(
            "rm -rf", "curl | sh", "curl|sh", "wget | sh", "wget|sh", "powershell -enc"
    );

    private final StructuredSyntaxValidationService syntaxValidationService;

    public CreatePreWriteValidationService(StructuredSyntaxValidationService syntaxValidationService) {
        this.syntaxValidationService = syntaxValidationService;
    }

    public ValidationResult validate(List<PatchOperation> operations) {
        Instant startedAt = Instant.now();
        List<String> errors = new ArrayList<>();
        if (operations == null || operations.isEmpty()) {
            errors.add("patch_operations_empty");
            return new ValidationResult(false, errors, 0);
        }
        for (PatchOperation operation : operations) {
            validateOperation(operation, errors);
        }
        return new ValidationResult(errors.isEmpty(), errors, Duration.between(startedAt, Instant.now()).toMillis());
    }

    private void validateOperation(PatchOperation operation, List<String> errors) {
        if (operation == null) {
            errors.add("operation_null");
            return;
        }
        String relativePath = StrUtil.blankToDefault(operation.relativePath(), "");
        String content = operationContent(operation);
        if (StrUtil.isBlank(relativePath)) {
            errors.add("path_missing");
            return;
        }
        if (containsSecret(content)) {
            errors.add(relativePath + ":secret_or_private_address_detected");
        }
        if (hasCompleteFileContent(operation)) {
            StructuredSyntaxValidationService.ValidationResult syntaxResult = syntaxValidationService.validate(relativePath, content);
            errors.addAll(syntaxResult.errors());
        }
        if (relativePath.endsWith("package.json")) {
            validatePackageJson(relativePath, content, errors);
        } else if (relativePath.endsWith(".json")) {
            validateJson(relativePath, content, errors);
        } else if (relativePath.endsWith(".vue")) {
            validateVue(relativePath, content, errors);
        } else if (isJavaScriptLike(relativePath)) {
            validateJavaScriptLike(relativePath, content, errors);
        } else if (relativePath.endsWith(".go")) {
            validateGo(relativePath, operation, content, errors);
        } else if (relativePath.endsWith(".sql")) {
            validateSql(relativePath, content, errors);
        }
    }

    private void validatePackageJson(String relativePath, String content, List<String> errors) {
        validateJson(relativePath, content, errors);
        String lower = StrUtil.blankToDefault(content, "").toLowerCase(Locale.ROOT);
        for (String token : DANGEROUS_SCRIPT_TOKENS) {
            if (lower.contains(token)) {
                errors.add(relativePath + ":dangerous_package_script:" + token);
            }
        }
        if (!lower.contains("\"scripts\"")) {
            errors.add(relativePath + ":scripts_missing");
        }
    }

    private void validateJson(String relativePath, String content, List<String> errors) {
        if (StrUtil.isBlank(content)) {
            return;
        }
        try {
            JSONUtil.parse(content);
        } catch (Exception e) {
            errors.add(relativePath + ":invalid_json");
        }
    }

    private void validateVue(String relativePath, String content, List<String> errors) {
        if (StrUtil.isBlank(content)) {
            return;
        }
        if (!content.contains("<template") || !content.contains("</template>")) {
            errors.add(relativePath + ":vue_template_missing");
        }
        if (count(content, "<script") != count(content, "</script>")) {
            errors.add(relativePath + ":vue_script_tag_unbalanced");
        }
        validateJavaScriptLike(relativePath, content, errors);
    }

    private void validateJavaScriptLike(String relativePath, String content, List<String> errors) {
        if (StrUtil.isBlank(content)) {
            return;
        }
        if (content.contains("import ") && hasUnbalancedQuotes(content)) {
            errors.add(relativePath + ":import_quotes_unbalanced");
        }
        if (content.contains(" from ''") || content.contains(" from \"\"")) {
            errors.add(relativePath + ":empty_import_specifier");
        }
    }

    private void validateGo(String relativePath, PatchOperation operation, String content, List<String> errors) {
        if (StrUtil.isBlank(content)) {
            return;
        }
        if (PatchOperation.ACTION_GO_ADD_IMPORT.equals(operation.action())) {
            validateGoImportPath(relativePath, content, errors);
            return;
        }
        if (!PatchOperation.ACTION_ADD.equals(operation.action())
                && !PatchOperation.ACTION_MODIFY.equals(operation.action())
                && !PatchOperation.ACTION_REPLACE.equals(operation.action())) {
            return;
        }
        if (!content.stripLeading().startsWith("package ")) {
            errors.add(relativePath + ":go_package_missing");
        }
        if (content.contains("import (") && count(content, "import (") > count(content, ")")) {
            errors.add(relativePath + ":go_import_block_unbalanced");
        }
        validateGoPackageMatchesModulePath(relativePath, content, errors);
    }

    private void validateSql(String relativePath, String content, List<String> errors) {
        String lower = StrUtil.blankToDefault(content, "").toLowerCase(Locale.ROOT);
        if (lower.contains("drop table") || lower.contains("drop database") || lower.contains("truncate table")) {
            errors.add(relativePath + ":dangerous_sql");
        }
        if (lower.contains("create table") && !lower.contains("if not exists")) {
            errors.add(relativePath + ":create_table_without_if_not_exists");
        }
        if (relativePath.endsWith("sql/schema.sql")
                && StrUtil.isNotBlank(lower)
                && !lower.contains("create table if not exists")) {
            errors.add(relativePath + ":schema_create_table_missing");
        }
    }

    private void validateGoImportPath(String relativePath, String content, List<String> errors) {
        String normalized = StrUtil.blankToDefault(content, "").trim();
        if (normalized.contains("\"")
                || normalized.contains("'")
                || normalized.contains(";")
                || normalized.contains("\\")
                || normalized.contains("import ")
                || normalized.contains("\n")
                || normalized.contains("\r")
                || !GO_IMPORT_PATH_PATTERN.matcher(normalized).matches()) {
            errors.add(relativePath + ":go_import_path_invalid");
        }
    }

    private void validateGoPackageMatchesModulePath(String relativePath, String content, List<String> errors) {
        String normalizedPath = StrUtil.blankToDefault(relativePath, "").replace('\\', '/');
        java.util.regex.Matcher pathMatcher = GO_MODULE_PACKAGE_PATH_PATTERN.matcher(normalizedPath);
        if (!pathMatcher.find()) {
            return;
        }
        java.util.regex.Matcher packageMatcher = GO_PACKAGE_PATTERN.matcher(content);
        if (packageMatcher.find() && !pathMatcher.group(1).equals(packageMatcher.group(1))) {
            errors.add(relativePath + ":go_package_module_mismatch");
        }
    }

    private String operationContent(PatchOperation operation) {
        return switch (operation.action()) {
            case PatchOperation.ACTION_ADD, PatchOperation.ACTION_MODIFY -> operation.content();
            case PatchOperation.ACTION_REPLACE,
                 PatchOperation.ACTION_INSERT_BEFORE_MARKER,
                 PatchOperation.ACTION_INSERT_AFTER_MARKER,
                 PatchOperation.ACTION_GO_ADD_IMPORT,
                 PatchOperation.ACTION_GO_APPEND_TO_FUNCTION,
                 PatchOperation.ACTION_GO_ADD_STRUCT_FIELDS,
                 PatchOperation.ACTION_APPEND_SQL_MIGRATION -> operation.newContent();
            default -> "";
        };
    }

    private boolean hasCompleteFileContent(PatchOperation operation) {
        return PatchOperation.ACTION_ADD.equals(operation.action())
                || PatchOperation.ACTION_MODIFY.equals(operation.action());
    }

    private boolean containsSecret(String content) {
        String normalized = StrUtil.blankToDefault(content, "");
        return SECRET_PATTERN.matcher(normalized).find()
                || PRIVATE_ADDRESS_PATTERN.matcher(normalized).find();
    }

    private boolean isJavaScriptLike(String relativePath) {
        return relativePath.endsWith(".js")
                || relativePath.endsWith(".mjs")
                || relativePath.endsWith(".ts")
                || relativePath.endsWith(".jsx")
                || relativePath.endsWith(".tsx");
    }

    private boolean hasUnbalancedQuotes(String content) {
        return count(content, "'") % 2 != 0 || count(content, "\"") % 2 != 0;
    }

    private int count(String content, String needle) {
        if (StrUtil.isBlank(content) || StrUtil.isBlank(needle)) {
            return 0;
        }
        int count = 0;
        int index = 0;
        while ((index = content.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }

    public record ValidationResult(
            boolean valid,
            List<String> errors,
            long durationMs
    ) {
        public ValidationResult {
            errors = errors == null ? List.of() : List.copyOf(errors);
        }
    }
}
