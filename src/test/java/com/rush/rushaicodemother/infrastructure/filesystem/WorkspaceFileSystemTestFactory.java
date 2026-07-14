package com.rush.rushaicodemother.infrastructure.filesystem;

import com.rush.rushaicodemother.config.WorkspaceFileSystemProperties;

/** Creates the production workspace file-system service with default limits for unit tests. */
public final class WorkspaceFileSystemTestFactory {

    private WorkspaceFileSystemTestFactory() {
    }

    public static WorkspaceFileSystemService create() {
        return new WorkspaceFileSystemService(new WorkspaceFileSystemProperties());
    }
}
