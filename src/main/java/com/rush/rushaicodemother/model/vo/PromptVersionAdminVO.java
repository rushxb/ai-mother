package com.rush.rushaicodemother.model.vo;

/**
 * 提示词版本管理端接口视图对象。
 */
public record PromptVersionAdminVO(
        String version,
        String contentHash
) {
}
