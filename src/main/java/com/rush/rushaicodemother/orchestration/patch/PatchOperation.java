package com.rush.rushaicodemother.orchestration.patch;

import cn.hutool.core.util.StrUtil;

/**
 * 独立补丁执行器支持的文件级操作。
 */
public record PatchOperation(
        String action,
        String relativePath,
        String content,
        String oldContent,
        String newContent
) {

    public static final String ACTION_ADD = "add";
    public static final String ACTION_MODIFY = "modify";
    public static final String ACTION_REPLACE = "replace";
    public static final String ACTION_DELETE = "delete";
    public static final String ACTION_INSERT_BEFORE_MARKER = "insert_before_marker";
    public static final String ACTION_INSERT_AFTER_MARKER = "insert_after_marker";
    public static final String ACTION_GO_ADD_IMPORT = "go_add_import";
    public static final String ACTION_GO_APPEND_TO_FUNCTION = "go_append_to_function";
    public static final String ACTION_GO_ADD_STRUCT_FIELDS = "go_add_struct_fields";
    public static final String ACTION_APPEND_SQL_MIGRATION = "append_sql_migration";

    public PatchOperation {
        action = StrUtil.blankToDefault(action, "").trim().toLowerCase();
        relativePath = StrUtil.blankToDefault(relativePath, "").trim();
    }

    public static PatchOperation add(String relativePath, String content) {
        return new PatchOperation(ACTION_ADD, relativePath, content, null, null);
    }

    public static PatchOperation modify(String relativePath, String content) {
        return new PatchOperation(ACTION_MODIFY, relativePath, content, null, null);
    }

    public static PatchOperation replace(String relativePath, String oldContent, String newContent) {
        return new PatchOperation(ACTION_REPLACE, relativePath, null, oldContent, newContent);
    }

    public static PatchOperation insertBeforeMarker(String relativePath, String marker, String content) {
        return new PatchOperation(ACTION_INSERT_BEFORE_MARKER, relativePath, null, marker, content);
    }

    public static PatchOperation insertAfterMarker(String relativePath, String marker, String content) {
        return new PatchOperation(ACTION_INSERT_AFTER_MARKER, relativePath, null, marker, content);
    }

    public static PatchOperation goAddImport(String relativePath, String importPath) {
        return new PatchOperation(ACTION_GO_ADD_IMPORT, relativePath, null, null, importPath);
    }

    public static PatchOperation goAppendToFunction(String relativePath, String functionName, String snippet) {
        return new PatchOperation(ACTION_GO_APPEND_TO_FUNCTION, relativePath, null, functionName, snippet);
    }

    public static PatchOperation goAddStructFields(String relativePath, String structName, String fields) {
        return new PatchOperation(ACTION_GO_ADD_STRUCT_FIELDS, relativePath, null, structName, fields);
    }

    public static PatchOperation appendSqlMigration(String relativePath, String migrationSql) {
        return new PatchOperation(ACTION_APPEND_SQL_MIGRATION, relativePath, null, null, migrationSql);
    }

    public static PatchOperation delete(String relativePath) {
        return new PatchOperation(ACTION_DELETE, relativePath, null, null, null);
    }
}
