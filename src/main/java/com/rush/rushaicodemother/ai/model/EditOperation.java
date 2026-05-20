package com.rush.rushaicodemother.ai.model;

/**
 * AI 编辑操作结果。
 */
public record EditOperation(
        String action,
        String relativePath,
        String oldContent,
        String newContent,
        String content
) {
}
