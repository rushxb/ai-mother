package com.rush.rushaicodemother.ai.model;

import java.util.List;

/**
 * AI 编辑结果。
 */
public record EditResult(
        String summary,
        List<EditOperation> operations,
        EditValidation validation
) {

    public record EditValidation(boolean requiresBuild, String reason) {
    }
}
