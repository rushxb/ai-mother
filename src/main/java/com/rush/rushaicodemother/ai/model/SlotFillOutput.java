package com.rush.rushaicodemother.ai.model;

import java.util.List;

/**
 * AI slot 填充输出。
 */
public record SlotFillOutput(
        String summary,
        List<SlotContent> slots,
        boolean requiresBuild
) {
    /**
     * 单个 slot 的填充内容。
     */
    public record SlotContent(
            String slotId,
            String content,
            String reason
    ) {
    }
}
