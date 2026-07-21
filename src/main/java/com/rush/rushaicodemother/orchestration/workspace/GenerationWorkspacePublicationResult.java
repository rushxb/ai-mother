package com.rush.rushaicodemother.orchestration.workspace;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;

/** Result of one fenced workspace publication attempt. */
public record GenerationWorkspacePublicationResult(
        Status status,
        GenerationWorkspacePublicationPointer pointer,
        Path publishedWorkspace,
        Instant completedAt
) {

    public GenerationWorkspacePublicationResult {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(pointer, "pointer");
        publishedWorkspace = Objects.requireNonNull(publishedWorkspace, "publishedWorkspace")
                .toAbsolutePath().normalize();
        Objects.requireNonNull(completedAt, "completedAt");
    }

    public enum Status {
        PUBLISHED,
        ALREADY_PUBLISHED
    }
}
