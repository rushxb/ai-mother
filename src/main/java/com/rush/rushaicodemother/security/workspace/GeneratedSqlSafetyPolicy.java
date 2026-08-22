package com.rush.rushaicodemother.security.workspace;

import cn.hutool.core.util.StrUtil;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 生成 SQL 的统一安全策略。
 *
 * <p>调用方只需要提交真实待写入或待评测的 SQL；危险语句集合和稳定拒绝原因由本模块集中维护，
 * 避免 CREATE、EDIT、结构化补丁和 Benchmark 各自维护关键词。</p>
 */
@Component
public class GeneratedSqlSafetyPolicy {

    private static final List<ForbiddenSqlRule> FORBIDDEN_RULES = List.of(
            rule("(?i)\\bdrop\\s+(?:temp(?:orary)?\\s+)?table\\b", "drop_table"),
            rule("(?i)\\bdrop\\s+database\\b", "drop_database"),
            rule("(?i)\\btruncate\\s+table\\b", "truncate_table"),
            rule("(?i)\\bpragma\\s+(?:[a-z_][a-z0-9_]*\\s*\\.\\s*)?writable_schema\\b",
                    "pragma_writable_schema")
    );

    /**
     * 返回 SQL 中命中的稳定安全拒绝原因；空集合表示未发现禁止语句。
     */
    public List<String> validateAll(String sql) {
        SqlScanResult scanResult = scanExecutableSql(StrUtil.blankToDefault(sql, ""));
        List<String> violations = new ArrayList<>();
        if (StrUtil.isNotBlank(scanResult.malformedReason())) {
            violations.add(scanResult.malformedReason());
        }
        for (ForbiddenSqlRule rule : FORBIDDEN_RULES) {
            if (rule.pattern().matcher(scanResult.executableSql()).find()) {
                violations.add("generated_sql_forbidden_statement:" + rule.name());
            }
        }
        return List.copyOf(violations);
    }

    /**
     * 屏蔽注释、字符串和带引号标识符，只保留数据库实际解释为语句关键字的文本。
     * 使用空白占位可防止关键字之间插入块注释来规避规则。
     */
    private SqlScanResult scanExecutableSql(String sql) {
        StringBuilder executable = new StringBuilder(sql.length());
        SqlLexicalState state = SqlLexicalState.NORMAL;
        for (int index = 0; index < sql.length(); index++) {
            char current = sql.charAt(index);
            char next = index + 1 < sql.length() ? sql.charAt(index + 1) : '\0';

            if (state == SqlLexicalState.NORMAL) {
                if (current == '-' && next == '-') {
                    executable.append("  ");
                    index++;
                    state = SqlLexicalState.LINE_COMMENT;
                } else if (current == '/' && next == '*') {
                    executable.append("  ");
                    index++;
                    state = SqlLexicalState.BLOCK_COMMENT;
                } else if (current == '\'') {
                    executable.append(' ');
                    state = SqlLexicalState.SINGLE_QUOTED_VALUE;
                } else if (current == '"') {
                    executable.append(' ');
                    state = SqlLexicalState.DOUBLE_QUOTED_IDENTIFIER;
                } else if (current == '`') {
                    executable.append(' ');
                    state = SqlLexicalState.BACKTICK_QUOTED_IDENTIFIER;
                } else if (current == '[') {
                    executable.append(' ');
                    state = SqlLexicalState.BRACKET_QUOTED_IDENTIFIER;
                } else {
                    executable.append(current);
                }
                continue;
            }

            if (state == SqlLexicalState.LINE_COMMENT) {
                if (current == '\n' || current == '\r') {
                    executable.append(current);
                    state = SqlLexicalState.NORMAL;
                } else {
                    executable.append(' ');
                }
                continue;
            }

            if (state == SqlLexicalState.BLOCK_COMMENT) {
                executable.append(current == '\n' || current == '\r' ? current : ' ');
                if (current == '*' && next == '/') {
                    executable.append(' ');
                    index++;
                    state = SqlLexicalState.NORMAL;
                }
                continue;
            }

            executable.append(current == '\n' || current == '\r' ? current : ' ');
            if (isEscapedPair(state, current, next)) {
                executable.append(' ');
                index++;
            } else if (closesQuotedValue(state, current)) {
                state = SqlLexicalState.NORMAL;
            }
        }
        return new SqlScanResult(executable.toString(), malformedReason(state));
    }

    private String malformedReason(SqlLexicalState state) {
        return switch (state) {
            case NORMAL, LINE_COMMENT -> "";
            case BLOCK_COMMENT -> "generated_sql_malformed:unterminated_block_comment";
            case SINGLE_QUOTED_VALUE -> "generated_sql_malformed:unterminated_string_literal";
            case DOUBLE_QUOTED_IDENTIFIER,
                 BACKTICK_QUOTED_IDENTIFIER,
                 BRACKET_QUOTED_IDENTIFIER -> "generated_sql_malformed:unterminated_quoted_identifier";
        };
    }

    private boolean isEscapedPair(SqlLexicalState state, char current, char next) {
        return state == SqlLexicalState.SINGLE_QUOTED_VALUE && current == '\'' && next == '\''
                || state == SqlLexicalState.DOUBLE_QUOTED_IDENTIFIER && current == '"' && next == '"'
                || state == SqlLexicalState.BACKTICK_QUOTED_IDENTIFIER && current == '`' && next == '`'
                || state == SqlLexicalState.BRACKET_QUOTED_IDENTIFIER && current == ']' && next == ']';
    }

    private boolean closesQuotedValue(SqlLexicalState state, char current) {
        return state == SqlLexicalState.SINGLE_QUOTED_VALUE && current == '\''
                || state == SqlLexicalState.DOUBLE_QUOTED_IDENTIFIER && current == '"'
                || state == SqlLexicalState.BACKTICK_QUOTED_IDENTIFIER && current == '`'
                || state == SqlLexicalState.BRACKET_QUOTED_IDENTIFIER && current == ']';
    }

    private static ForbiddenSqlRule rule(String expression, String name) {
        return new ForbiddenSqlRule(Pattern.compile(expression), name);
    }

    private record ForbiddenSqlRule(Pattern pattern, String name) {
    }

    private record SqlScanResult(String executableSql, String malformedReason) {
    }

    private enum SqlLexicalState {
        NORMAL,
        LINE_COMMENT,
        BLOCK_COMMENT,
        SINGLE_QUOTED_VALUE,
        DOUBLE_QUOTED_IDENTIFIER,
        BACKTICK_QUOTED_IDENTIFIER,
        BRACKET_QUOTED_IDENTIFIER
    }
}
