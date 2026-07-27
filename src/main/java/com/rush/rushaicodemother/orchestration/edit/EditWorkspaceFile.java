package com.rush.rushaicodemother.orchestration.edit;

import java.nio.file.Path;

/** 已通过编辑工作空间路径策略的常规文件。 */
public record EditWorkspaceFile(String relativePath, Path absolutePath) {

    public String fileName() {
        int separatorIndex = relativePath.lastIndexOf('/');
        return separatorIndex < 0 ? relativePath : relativePath.substring(separatorIndex + 1);
    }
}
