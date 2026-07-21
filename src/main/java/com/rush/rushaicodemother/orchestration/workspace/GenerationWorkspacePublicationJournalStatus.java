package com.rush.rushaicodemother.orchestration.workspace;

import java.util.Arrays;

/** Durable saga state for coordinating filesystem publication with relational metadata. */
public enum GenerationWorkspacePublicationJournalStatus {
    PREPARED("prepared"),
    FILESYSTEM_ACTIVATED("filesystem_activated"),
    COMMITTED("committed"),
    ROLLBACK_REQUIRED("rollback_required"),
    ROLLED_BACK("rolled_back"),
    SUPERSEDED("superseded");

    private final String value;

    GenerationWorkspacePublicationJournalStatus(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static GenerationWorkspacePublicationJournalStatus fromValue(String value) {
        return Arrays.stream(values())
                .filter(status -> status.value.equals(value))
                .findFirst()
                .orElse(null);
    }
}
