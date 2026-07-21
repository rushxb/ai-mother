package com.rush.rushaicodemother.infrastructure.persistence.task;

import com.rush.rushaicodemother.mapper.GenerationWorkspacePublicationJournalMapper;
import com.rush.rushaicodemother.model.entity.GenerationTask;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspacePublicationJournalEntry;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspacePublicationJournalRepository;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspacePublicationJournalStatus;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspacePublicationPointer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

@Repository
@RequiredArgsConstructor
public class MyBatisGenerationWorkspacePublicationJournalRepository
        implements GenerationWorkspacePublicationJournalRepository {

    private static final int MAX_BATCH_SIZE = 1000;
    private static final int MAX_ERROR_LENGTH = 1024;

    private final GenerationWorkspacePublicationJournalMapper mapper;
    private final ZoneId databaseZone = ZoneId.systemDefault();

    @Override
    @Transactional
    public GenerationWorkspacePublicationJournalEntry prepare(
            GenerationWorkspacePublicationPointer candidate,
            Instant preparedAt) {
        requirePointer(candidate);
        if (preparedAt == null) {
            throw new IllegalArgumentException("publication prepared timestamp is required");
        }
        GenerationWorkspacePublicationJournalEntry existing = toEntry(
                mapper.selectOne(candidate.taskId()), false);
        if (existing == null) {
            mapper.prepareNew(
                    candidate.taskId(), candidate.appId(), candidate.codeGenType().getValue(),
                    candidate.executionEpoch(), toLocal(candidate.publishedAt()), toLocal(preparedAt));
            existing = toEntry(mapper.selectOne(candidate.taskId()), true);
        }
        if (!existing.sameExecution(candidate)) {
            throw new IllegalStateException("generation task has a conflicting publication journal");
        }
        if (existing.status() == GenerationWorkspacePublicationJournalStatus.ROLLED_BACK) {
            mapper.reopen(
                    existing.taskId(), existing.appId(), existing.codeGenType().getValue(),
                    existing.executionEpoch(), toLocal(existing.publishedAt()), toLocal(preparedAt));
            existing = toEntry(mapper.selectOne(candidate.taskId()), true);
            if (existing.status() == GenerationWorkspacePublicationJournalStatus.ROLLED_BACK) {
                throw new IllegalStateException("rolled back publication journal could not be reopened");
            }
        }
        if (existing.status() == GenerationWorkspacePublicationJournalStatus.SUPERSEDED) {
            throw new IllegalStateException("superseded publication cannot be reopened");
        }
        return existing;
    }

    @Override
    public void markFilesystemActivated(GenerationWorkspacePublicationPointer pointer,
                                        Instant activatedAt) {
        requirePointer(pointer);
        requireChanged(mapper.markFilesystemActivated(
                pointer.taskId(), pointer.appId(), pointer.codeGenType().getValue(),
                pointer.executionEpoch(), toLocal(pointer.publishedAt()), toLocal(activatedAt)),
                pointer,
                GenerationWorkspacePublicationJournalStatus.FILESYSTEM_ACTIVATED);
    }

    @Override
    public void markCommitted(GenerationWorkspacePublicationPointer pointer, Instant committedAt) {
        requirePointer(pointer);
        requireChanged(mapper.markCommitted(
                pointer.taskId(), pointer.appId(), pointer.codeGenType().getValue(),
                pointer.executionEpoch(), toLocal(pointer.publishedAt()), toLocal(committedAt)),
                pointer,
                GenerationWorkspacePublicationJournalStatus.COMMITTED);
    }

    @Override
    public void markRolledBack(GenerationWorkspacePublicationPointer pointer,
                               String error,
                               Instant rolledBackAt) {
        markTerminal(pointer, GenerationWorkspacePublicationJournalStatus.ROLLED_BACK,
                error, rolledBackAt);
    }

    @Override
    public void markRollbackRequired(GenerationWorkspacePublicationPointer pointer,
                                     String error,
                                     Instant failedAt) {
        markTerminal(pointer, GenerationWorkspacePublicationJournalStatus.ROLLBACK_REQUIRED,
                error, failedAt);
    }

    @Override
    public void markSuperseded(GenerationWorkspacePublicationPointer pointer,
                               String reason,
                               Instant supersededAt) {
        markTerminal(pointer, GenerationWorkspacePublicationJournalStatus.SUPERSEDED,
                reason, supersededAt);
    }

    @Override
    @Transactional
    public List<GenerationWorkspacePublicationJournalEntry> claimPending(
            Instant now,
            int limit,
            int maxAttempts,
            Duration retryDelay) {
        if (now == null || limit <= 0 || limit > MAX_BATCH_SIZE || maxAttempts <= 0
                || retryDelay == null || retryDelay.isZero() || retryDelay.isNegative()) {
            throw new IllegalArgumentException("publication reconciliation claim is invalid");
        }
        LocalDateTime claimedAt = toLocal(now);
        LocalDateTime retryAt = toLocal(now.plus(retryDelay));
        List<GenerationTask> candidates = mapper.selectPending(claimedAt, limit, maxAttempts);
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        List<GenerationWorkspacePublicationJournalEntry> claimed = new ArrayList<>();
        for (GenerationTask candidate : candidates) {
            long expectedVersion = candidate.getPublicationVersion() == null
                    ? 0L : candidate.getPublicationVersion();
            if (mapper.claim(candidate.getTaskId(), expectedVersion, maxAttempts,
                    claimedAt, retryAt) == 1) {
                GenerationWorkspacePublicationJournalEntry entry = toEntry(candidate, true);
                claimed.add(new GenerationWorkspacePublicationJournalEntry(
                        entry.taskId(), entry.appId(), entry.codeGenType(), entry.executionEpoch(),
                        entry.publishedAt(), entry.status(), entry.attempts() + 1,
                        entry.version() + 1, ""));
            }
        }
        return List.copyOf(claimed);
    }

    @Override
    public void recordReconciliationFailure(GenerationWorkspacePublicationPointer pointer,
                                            String error,
                                            Instant failedAt) {
        requirePointer(pointer);
        int changed = mapper.recordFailure(
                pointer.taskId(), pointer.appId(), pointer.codeGenType().getValue(),
                pointer.executionEpoch(), toLocal(pointer.publishedAt()),
                normalizeError(error), toLocal(failedAt));
        if (changed == 1) {
            return;
        }
        GenerationWorkspacePublicationJournalEntry current = toEntry(
                mapper.selectOne(pointer.taskId()), true);
        if (!current.samePublication(pointer) || isPending(current.status())) {
            throw new IllegalStateException("publication reconciliation failure was not recorded");
        }
    }

    private void markTerminal(GenerationWorkspacePublicationPointer pointer,
                              GenerationWorkspacePublicationJournalStatus status,
                              String error,
                              Instant changedAt) {
        requirePointer(pointer);
        if (changedAt == null) {
            throw new IllegalArgumentException("publication journal timestamp is required");
        }
        int changed = mapper.markTerminal(
                pointer.taskId(), pointer.appId(), pointer.codeGenType().getValue(),
                pointer.executionEpoch(), toLocal(pointer.publishedAt()), status.value(),
                normalizeError(error), toLocal(changedAt));
        requireChanged(changed, pointer, status);
    }

    private void requireChanged(int changed,
                                GenerationWorkspacePublicationPointer pointer,
                                GenerationWorkspacePublicationJournalStatus expectedStatus) {
        requirePointer(pointer);
        if (changed == 1) {
            return;
        }
        GenerationWorkspacePublicationJournalEntry current = toEntry(
                mapper.selectOne(pointer.taskId()), true);
        if (!current.samePublication(pointer) || current.status() != expectedStatus) {
            throw new IllegalStateException("publication journal transition was rejected");
        }
    }

    private boolean isPending(GenerationWorkspacePublicationJournalStatus status) {
        return status == GenerationWorkspacePublicationJournalStatus.PREPARED
                || status == GenerationWorkspacePublicationJournalStatus.FILESYSTEM_ACTIVATED
                || status == GenerationWorkspacePublicationJournalStatus.ROLLBACK_REQUIRED;
    }

    private GenerationWorkspacePublicationJournalEntry toEntry(GenerationTask task,
                                                                boolean required) {
        if (task == null || task.getPublicationStatus() == null) {
            if (required) {
                throw new IllegalStateException("publication journal does not exist");
            }
            return null;
        }
        GenerationWorkspacePublicationJournalStatus status =
                GenerationWorkspacePublicationJournalStatus.fromValue(task.getPublicationStatus());
        CodeGenTypeEnum codeGenType = CodeGenTypeEnum.getEnumByValue(task.getPublicationCodeGenType());
        if (status == null || codeGenType == null || task.getPublicationExecutionEpoch() == null
                || task.getPublicationPublishedAt() == null) {
            throw new IllegalStateException("publication journal data is malformed");
        }
        return new GenerationWorkspacePublicationJournalEntry(
                task.getTaskId(), task.getAppId(), codeGenType,
                task.getPublicationExecutionEpoch(), toInstant(task.getPublicationPublishedAt()),
                status,
                task.getPublicationAttempts() == null ? 0 : task.getPublicationAttempts(),
                task.getPublicationVersion() == null ? 0L : task.getPublicationVersion(),
                task.getPublicationError());
    }

    private void requirePointer(GenerationWorkspacePublicationPointer pointer) {
        if (pointer == null) {
            throw new IllegalArgumentException("publication pointer is required");
        }
    }

    private String normalizeError(String error) {
        if (error == null) {
            return "";
        }
        return error.length() <= MAX_ERROR_LENGTH ? error : error.substring(0, MAX_ERROR_LENGTH);
    }

    private LocalDateTime toLocal(Instant instant) {
        if (instant == null) {
            throw new IllegalArgumentException("publication timestamp is required");
        }
        return LocalDateTime.ofInstant(instant, databaseZone);
    }

    private Instant toInstant(LocalDateTime value) {
        return value.atZone(databaseZone).toInstant();
    }
}
