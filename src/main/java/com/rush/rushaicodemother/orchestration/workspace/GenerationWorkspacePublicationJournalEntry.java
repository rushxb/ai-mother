package com.rush.rushaicodemother.orchestration.workspace;

import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;

import java.time.Instant;
import java.util.Objects;

/** 一种持久的出版意图及其当前的传奇状态。 */
public record GenerationWorkspacePublicationJournalEntry(
        String taskId,
        Long appId,
        CodeGenTypeEnum codeGenType,
        long executionEpoch,
        Instant publishedAt,
        GenerationWorkspacePublicationJournalStatus status,
        int attempts,
        long version,
        String lastError
) {
    public GenerationWorkspacePublicationJournalEntry {
        if (taskId == null || !taskId.matches("[A-Za-z0-9_-]{1,128}")) {
            throw new IllegalArgumentException("publication journal task identity is invalid");
        }
        if (appId == null || appId <= 0 || codeGenType == null
                || executionEpoch <= 0 || publishedAt == null || status == null
                || attempts < 0 || version < 0) {
            throw new IllegalArgumentException("publication journal state is invalid");
        }
        lastError = lastError == null ? "" : lastError;
    }

    public GenerationWorkspacePublicationPointer pointer() {
        return new GenerationWorkspacePublicationPointer(
                GenerationWorkspacePublicationPointer.CURRENT_SCHEMA_VERSION,
                appId,
                codeGenType,
                taskId,
                executionEpoch,
                publishedAt
        );
    }

    public boolean sameExecution(GenerationWorkspacePublicationPointer pointer) {
        return pointer != null
                && Objects.equals(taskId, pointer.taskId())
                && Objects.equals(appId, pointer.appId())
                && codeGenType == pointer.codeGenType()
                && executionEpoch == pointer.executionEpoch();
    }

    public boolean samePublication(GenerationWorkspacePublicationPointer pointer) {
        return sameExecution(pointer) && Objects.equals(publishedAt, pointer.publishedAt());
    }
}
