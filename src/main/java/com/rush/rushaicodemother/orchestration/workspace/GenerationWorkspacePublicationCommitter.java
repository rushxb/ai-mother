package com.rush.rushaicodemother.orchestration.workspace;

/** Commits relational publication metadata and the journal state in one database transaction. */
@FunctionalInterface
public interface GenerationWorkspacePublicationCommitter {

    void commit(GenerationWorkspacePublicationPointer pointer);
}
