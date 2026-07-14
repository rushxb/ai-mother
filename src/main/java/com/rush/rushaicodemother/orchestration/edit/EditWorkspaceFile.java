package com.rush.rushaicodemother.orchestration.edit;

import java.nio.file.Path;

/** A regular file that has passed the edit-workspace path policy. */
public record EditWorkspaceFile(String relativePath, Path absolutePath) {

    public String fileName() {
        int separatorIndex = relativePath.lastIndexOf('/');
        return separatorIndex < 0 ? relativePath : relativePath.substring(separatorIndex + 1);
    }
}
