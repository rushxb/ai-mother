package com.rush.rushaicodemother.service.devserver;

import java.util.regex.Pattern;

/**
 * Dev Server 错误模式定义
 * <p>
 * Vite dev server 输出的错误有明确的语义模式，分为 Critical（阻断级）和 Warning（警告级）。
 */
public enum DevServerErrorPattern {

    // ========== Critical: 阻断级，页面一定无法正常渲染 ==========

    /**
     * 缺失依赖：import 的包不在 node_modules 中
     * 例: [vite] Pre-transform error: Failed to resolve import "mockjs" from "src/mocks/index.ts"
     */
    MISSING_IMPORT(
            "MISSING_IMPORT",
            Severity.CRITICAL,
            Pattern.compile("Failed to resolve import\\s+[\"']([^\"']+)[\"']"),
            "缺失依赖: %s"
    ),

    /**
     * 导出不存在：模块存在但没有指定的 export
     * 例: SyntaxError: The requested module '/src/data/landingData.js' does not provide an export named 'author'
     */
    MISSING_EXPORT(
            "MISSING_EXPORT",
            Severity.CRITICAL,
            Pattern.compile("does not provide an export named\\s+[\"']([^\"']+)[\"']"),
            "模块未导出: %s"
    ),

    /**
     * 模块解析失败
     * 例: Error: Cannot find module 'xxx'
     */
    MODULE_NOT_FOUND(
            "MODULE_NOT_FOUND",
            Severity.CRITICAL,
            Pattern.compile("Cannot find module\\s+[\"']([^\"']+)[\"']"),
            "模块不存在: %s"
    ),

    /**
     * Vue 组件注册失败（通常是 import 路径错误）
     * 例: [Vue warn]: Failed to resolve component: MyComponent
     */
    COMPONENT_RESOLUTION_FAILED(
            "COMPONENT_RESOLUTION_FAILED",
            Severity.CRITICAL,
            Pattern.compile("Failed to resolve component:\\s+(\\S+)"),
            "组件解析失败: %s"
    ),

    /**
     * 语法错误
     * 例: SyntaxError: Unexpected token '<'
     */
    SYNTAX_ERROR(
            "SYNTAX_ERROR",
            Severity.CRITICAL,
            Pattern.compile("SyntaxError:.*"),
            "语法错误"
    ),

    /**
     * Vite 内部服务器错误
     * 例: [vite] Internal server error: ...
     */
    VITE_INTERNAL_ERROR(
            "VITE_INTERNAL_ERROR",
            Severity.CRITICAL,
            Pattern.compile("\\[vite\\]\\s*Internal server error"),
            "Vite 内部错误"
    ),

    // ========== Warning: 警告级，页面可能部分异常 ==========

    /**
     * Vue 警告（非致命，但可能影响渲染）
     * 例: [Vue warn]: Extraneous non-emits event listeners
     */
    VUE_WARN(
            "VUE_WARN",
            Severity.WARNING,
            Pattern.compile("\\[Vue warn\\]\\s*(.+)"),
            "Vue 警告: %s"
    ),

    /**
     * 废弃 API 使用
     */
    DEPRECATION(
            "DEPRECATION",
            Severity.WARNING,
            Pattern.compile("(?i)deprecated|Deprecation"),
            "使用了废弃 API"
    ),

    /**
     * CSS 解析问题
     * 例: CssSyntaxError: ...
     */
    CSS_ERROR(
            "CSS_ERROR",
            Severity.WARNING,
            Pattern.compile("CssSyntaxError|PostCSS.*error"),
            "CSS 语法错误"
    ),

    /**
     * TypeScript 类型错误（dev server 有时也会报）
     * 例: TS2322: Type 'string' is not assignable to type 'number'.
     */
    TYPE_ERROR(
            "TYPE_ERROR",
            Severity.WARNING,
            Pattern.compile("TS\\d{4}:.*"),
            "类型错误: %s"
    );

    private final String code;
    private final Severity severity;
    private final Pattern pattern;
    private final String messageTemplate;

    DevServerErrorPattern(String code, Severity severity, Pattern pattern, String messageTemplate) {
        this.code = code;
        this.severity = severity;
        this.pattern = pattern;
        this.messageTemplate = messageTemplate;
    }

    public String getCode() {
        return code;
    }

    public Severity getSeverity() {
        return severity;
    }

    public Pattern getPattern() {
        return pattern;
    }

    public String getMessageTemplate() {
        return messageTemplate;
    }

    public boolean isCritical() {
        return severity == Severity.CRITICAL;
    }

    public enum Severity {
        CRITICAL,
        WARNING
    }
}
