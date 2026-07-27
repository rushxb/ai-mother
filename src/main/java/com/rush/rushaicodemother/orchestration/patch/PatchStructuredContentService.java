package com.rush.rushaicodemother.orchestration.patch;

import cn.hutool.core.util.StrUtil;
import org.springframework.stereotype.Component;

/** 用于结构化 Go 和 SQL 补丁操作的纯内容转换。 */
@Component
public class PatchStructuredContentService {

    public String transform(String action,
                            String originalContent,
                            PatchOperation operation) throws PatchWorkspaceException {
        return switch (action) {
            case PatchOperation.ACTION_GO_ADD_IMPORT ->
                    transformGoImport(originalContent, operation.newContent());
            case PatchOperation.ACTION_GO_APPEND_TO_FUNCTION ->
                    transformGoFunctionAppend(originalContent, operation.oldContent(), operation.newContent());
            case PatchOperation.ACTION_GO_ADD_STRUCT_FIELDS ->
                    transformGoStructFields(originalContent, operation.oldContent(), operation.newContent());
            case PatchOperation.ACTION_APPEND_SQL_MIGRATION ->
                    transformSqlMigrationAppend(originalContent, operation.newContent());
            default -> throw new PatchWorkspaceException("unsupported_structured_action");
        };
    }

    public boolean containsDangerousSql(String sql) {
        String normalized = StrUtil.blankToDefault(sql, "").toLowerCase();
        return normalized.contains("drop table")
                || normalized.contains("drop database")
                || normalized.contains("truncate table")
                || normalized.contains("delete from users")
                || normalized.contains("pragma writable_schema");
    }

    private String transformGoImport(String content, String importPath) throws PatchWorkspaceException {
        String normalizedImport = StrUtil.blankToDefault(importPath, "").trim();
        String quotedImport = "\"" + normalizedImport.replace("\"", "") + "\"";
        if (content.contains(quotedImport)) {
            return content;
        }
        if (content.contains("import (\n")) {
            return content.replace("import (\n", "import (\n\t" + quotedImport + "\n");
        }
        if (content.matches("(?s).*import\\s+\"[^\"]+\".*")) {
            return content.replaceFirst("import\\s+\"([^\"]+)\"", "import (\n\t\"$1\"\n\t" + quotedImport + "\n)");
        }
        int packageLineEnd = content.indexOf('\n');
        if (packageLineEnd < 0) {
            throw new PatchWorkspaceException("invalid_go_file_missing_package_line");
        }
        return content.substring(0, packageLineEnd + 1)
                + "\nimport " + quotedImport + "\n"
                + content.substring(packageLineEnd + 1);
    }

    private String transformGoFunctionAppend(String content,
                                             String functionName,
                                             String snippet) throws PatchWorkspaceException {
        int functionIndex = content.indexOf("func " + functionName);
        if (functionIndex < 0) {
            functionIndex = content.indexOf("func (" + functionName);
        }
        if (functionIndex < 0) {
            throw new PatchWorkspaceException("go_function_not_found:" + functionName);
        }
        int bodyStart = content.indexOf('{', functionIndex);
        int bodyEnd = findMatchingBrace(content, bodyStart);
        if (bodyStart < 0 || bodyEnd < 0) {
            throw new PatchWorkspaceException("go_function_body_not_found:" + functionName);
        }
        return content.substring(0, bodyEnd)
                + "\n" + ensureTrailingNewline(snippet)
                + content.substring(bodyEnd);
    }

    private String transformGoStructFields(String content,
                                           String structName,
                                           String fields) throws PatchWorkspaceException {
        int structIndex = content.indexOf("type " + structName + " struct");
        if (structIndex < 0) {
            throw new PatchWorkspaceException("go_struct_not_found:" + structName);
        }
        int bodyStart = content.indexOf('{', structIndex);
        int bodyEnd = findMatchingBrace(content, bodyStart);
        if (bodyStart < 0 || bodyEnd < 0) {
            throw new PatchWorkspaceException("go_struct_body_not_found:" + structName);
        }
        return content.substring(0, bodyEnd)
                + ensureTrailingNewline(fields)
                + content.substring(bodyEnd);
    }

    private String transformSqlMigrationAppend(String content, String migrationSql) {
        String normalizedMigration = ensureTrailingNewline(migrationSql);
        if (content.contains(normalizedMigration.trim())) {
            return content;
        }
        return ensureTrailingNewline(content)
                + "\n-- @AI_GENERATED_MIGRATION\n"
                + normalizedMigration;
    }

    private int findMatchingBrace(String content, int openBraceIndex) {
        if (openBraceIndex < 0) {
            return -1;
        }
        int depth = 0;
        for (int index = openBraceIndex; index < content.length(); index++) {
            char current = content.charAt(index);
            if (current == '{') {
                depth++;
            } else if (current == '}') {
                depth--;
                if (depth == 0) {
                    return index;
                }
            }
        }
        return -1;
    }

    private String ensureTrailingNewline(String value) {
        String normalized = StrUtil.blankToDefault(value, "").stripTrailing();
        return normalized.isEmpty() ? "" : normalized + System.lineSeparator();
    }
}
