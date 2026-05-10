package com.yupi.yuaicodemother.orchestration.patch;

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

    public static PatchOperation delete(String relativePath) {
        return new PatchOperation(ACTION_DELETE, relativePath, null, null, null);
    }
}
