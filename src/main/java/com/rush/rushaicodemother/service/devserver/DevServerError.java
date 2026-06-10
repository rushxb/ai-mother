package com.rush.rushaicodemother.service.devserver;

import java.util.regex.Matcher;

/**
 * 单个 Dev Server 错误实例
 */
public record DevServerError(
        DevServerErrorPattern pattern,
        String rawLine,
        String extractedValue,
        String message,
        String suggestion,
        int occurrenceCount
) {

    /**
     * 从原始日志行尝试匹配所有错误模式
     *
     * @param line 原始日志行
     * @return 匹配到的第一个错误，null 表示无匹配
     */
    public static DevServerError tryMatch(String line) {
        if (line == null || line.isBlank()) {
            return null;
        }
        for (DevServerErrorPattern pattern : DevServerErrorPattern.values()) {
            Matcher matcher = pattern.getPattern().matcher(line);
            if (matcher.find()) {
                String extracted = matcher.groupCount() >= 1 ? matcher.group(1) : null;
                String message = extracted != null
                        ? String.format(pattern.getMessageTemplate(), extracted)
                        : pattern.getMessageTemplate();
                String suggestion = suggestFix(pattern, extracted);
                return new DevServerError(pattern, line.trim(), extracted, message, suggestion, 1);
            }
        }
        return null;
    }

    /**
     * 返回修复建议
     */
    private static String suggestFix(DevServerErrorPattern pattern, String extractedValue) {
        return switch (pattern) {
            case MISSING_IMPORT -> "检查 package.json 是否声明了依赖 '" + extractedValue
                    + "'，或确认 import 路径是否正确";
            case MISSING_EXPORT -> "检查模块是否正确定义并 export 了 '" + extractedValue + "'";
            case MODULE_NOT_FOUND -> "运行 pnpm add " + extractedValue + " 安装缺失模块";
            case COMPONENT_RESOLUTION_FAILED -> "检查组件 '" + extractedValue
                    + "' 的 import 路径是否正确，或确认组件文件是否存在";
            case SYNTAX_ERROR -> "检查相关文件的语法，可能是 AI 生成的代码格式问题";
            case VITE_INTERNAL_ERROR -> "查看完整错误日志定位具体问题文件";
            case VUE_WARN -> "检查组件 props、事件绑定是否正确";
            case TYPE_ERROR -> "检查 TypeScript 类型定义是否匹配";
            case CSS_ERROR -> "检查 CSS/PostCSS 配置和样式文件语法";
            case DEPRECATION -> "建议更新到新版 API，当前使用的 API 已废弃";
        };
    }

    /**
     * 创建一个累加了出现次数的新错误实例
     */
    public DevServerError withIncrementedCount() {
        return new DevServerError(pattern, rawLine, extractedValue, message, suggestion, occurrenceCount + 1);
    }
}
