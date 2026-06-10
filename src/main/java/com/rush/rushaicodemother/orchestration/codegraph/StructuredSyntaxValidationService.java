package com.rush.rushaicodemother.orchestration.codegraph;

import cn.hutool.core.util.StrUtil;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

@Service
public class StructuredSyntaxValidationService {

    private static final Pattern GO_PACKAGE_PATTERN = Pattern.compile("(?m)^\\s*package\\s+[A-Za-z_][\\w]*\\s*$");
    private static final Pattern SQL_TABLE_PATTERN = Pattern.compile("(?is)create\\s+table\\s+(?:if\\s+not\\s+exists\\s+)?([A-Za-z_][\\w]*)\\s*\\((.*?)\\)");

    public ValidationResult validate(String relativePath, String content) {
        String normalizedPath = StrUtil.blankToDefault(relativePath, "").replace('\\', '/');
        String normalizedContent = StrUtil.blankToDefault(content, "");
        List<String> errors = new ArrayList<>();
        if (normalizedPath.endsWith(".vue")) {
            validateVue(normalizedPath, normalizedContent, errors);
        } else if (isJavaScriptLike(normalizedPath)) {
            validateJavaScriptLike(normalizedPath, normalizedContent, errors);
        } else if (normalizedPath.endsWith(".go")) {
            validateGo(normalizedPath, normalizedContent, errors);
        } else if (normalizedPath.endsWith(".sql")) {
            validateSql(normalizedPath, normalizedContent, errors);
        }
        return new ValidationResult(errors.isEmpty(), errors);
    }

    private void validateVue(String path, String content, List<String> errors) {
        if (StrUtil.isBlank(content)) {
            return;
        }
        requireBalancedTag(path, content, "template", errors);
        if (count(content, "<script") != count(content, "</script>")) {
            errors.add(path + ":ast_vue_script_tag_unbalanced");
        }
        if (count(content, "<style") != count(content, "</style>")) {
            errors.add(path + ":ast_vue_style_tag_unbalanced");
        }
        validateJavaScriptLike(path, extractScriptContent(content), errors);
    }

    private void validateJavaScriptLike(String path, String content, List<String> errors) {
        if (StrUtil.isBlank(content)) {
            return;
        }
        validateBalancedDelimiters(path, content, errors);
        if (Pattern.compile("(?m)^\\s*import\\s+.*\\sfrom\\s+['\"][^'\"]*$").matcher(content).find()) {
            errors.add(path + ":ast_import_specifier_unclosed");
        }
        if (Pattern.compile("(?m)^\\s*export\\s+default\\s*$").matcher(content).find()) {
            errors.add(path + ":ast_export_default_incomplete");
        }
    }

    private void validateGo(String path, String content, List<String> errors) {
        if (StrUtil.isBlank(content)) {
            return;
        }
        if (!GO_PACKAGE_PATTERN.matcher(content).find()) {
            errors.add(path + ":ast_go_package_missing");
        }
        validateBalancedDelimiters(path, content, errors);
        if (count(content, "import (") > 0 && count(content, "import (") > count(content, ")")) {
            errors.add(path + ":ast_go_import_block_unclosed");
        }
    }

    private void validateSql(String path, String content, List<String> errors) {
        String lower = StrUtil.blankToDefault(content, "").toLowerCase(Locale.ROOT);
        if (lower.contains("create table") && !SQL_TABLE_PATTERN.matcher(content).find()) {
            errors.add(path + ":ast_sql_create_table_unparseable");
        }
    }

    private void requireBalancedTag(String path, String content, String tag, List<String> errors) {
        if (count(content, "<" + tag) != count(content, "</" + tag + ">")) {
            errors.add(path + ":ast_vue_" + tag + "_tag_unbalanced");
        }
    }

    private void validateBalancedDelimiters(String path, String content, List<String> errors) {
        ArrayDeque<Character> stack = new ArrayDeque<>();
        boolean singleQuote = false;
        boolean doubleQuote = false;
        boolean templateQuote = false;
        boolean escaped = false;
        for (int i = 0; i < content.length(); i++) {
            char current = content.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (current == '\\') {
                escaped = true;
                continue;
            }
            if (current == '\'' && !doubleQuote && !templateQuote) {
                singleQuote = !singleQuote;
                continue;
            }
            if (current == '"' && !singleQuote && !templateQuote) {
                doubleQuote = !doubleQuote;
                continue;
            }
            if (current == '`' && !singleQuote && !doubleQuote) {
                templateQuote = !templateQuote;
                continue;
            }
            if (singleQuote || doubleQuote || templateQuote) {
                continue;
            }
            if (current == '(' || current == '{' || current == '[') {
                stack.push(current);
            } else if (current == ')' || current == '}' || current == ']') {
                if (stack.isEmpty() || !matches(stack.pop(), current)) {
                    errors.add(path + ":ast_delimiter_unbalanced");
                    return;
                }
            }
        }
        if (!stack.isEmpty()) {
            errors.add(path + ":ast_delimiter_unbalanced");
        }
        if (singleQuote || doubleQuote || templateQuote) {
            errors.add(path + ":ast_quote_unbalanced");
        }
    }

    private boolean matches(char left, char right) {
        return (left == '(' && right == ')')
                || (left == '{' && right == '}')
                || (left == '[' && right == ']');
    }

    private String extractScriptContent(String content) {
        StringBuilder builder = new StringBuilder();
        var matcher = Pattern.compile("(?is)<script\\b[^>]*>(.*?)</script>").matcher(content);
        while (matcher.find()) {
            builder.append(matcher.group(1)).append('\n');
        }
        return builder.toString();
    }

    private boolean isJavaScriptLike(String relativePath) {
        return relativePath.endsWith(".js")
                || relativePath.endsWith(".mjs")
                || relativePath.endsWith(".ts")
                || relativePath.endsWith(".jsx")
                || relativePath.endsWith(".tsx");
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

    public record ValidationResult(boolean valid, List<String> errors) {
        public ValidationResult {
            errors = errors == null ? List.of() : List.copyOf(errors);
        }
    }
}
