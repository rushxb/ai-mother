package com.rush.rushaicodemother.infrastructure.persistence.prompt;

import com.rush.rushaicodemother.ai.prompt.release.PromptReleaseAction;
import com.rush.rushaicodemother.ai.prompt.release.PromptReleaseConflictException;
import com.rush.rushaicodemother.ai.prompt.release.PromptReleaseHistoryEntry;
import com.rush.rushaicodemother.ai.prompt.release.PromptReleaseMutation;
import com.rush.rushaicodemother.ai.prompt.release.PromptReleaseRecord;
import com.rush.rushaicodemother.ai.prompt.release.PromptReleaseRepository;
import com.rush.rushaicodemother.ai.prompt.release.PromptReleaseSpec;
import com.rush.rushaicodemother.ai.prompt.release.PromptReleaseState;
import com.rush.rushaicodemother.mapper.AiPromptReleaseMapper;
import com.rush.rushaicodemother.model.entity.AiPromptReleaseEntity;
import com.rush.rushaicodemother.model.entity.AiPromptReleaseHistoryEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * MyBatis提示词发布持久化仓储。
 */
@Repository
@RequiredArgsConstructor
public class MyBatisPromptReleaseRepository implements PromptReleaseRepository {

    private static final Pattern KEY_PATTERN = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");
    private static final Pattern VERSION_PATTERN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,31}");
    private static final int MAX_CHANGE_NOTE_LENGTH = 512;

    private final AiPromptReleaseMapper mapper;

    @Override
    @Transactional(readOnly = true)
    public PromptReleaseState loadCurrent() {
        long bundleRevision = requireBundleRevision(mapper.selectBundleRevision());
        Map<String, PromptReleaseRecord> releases = new LinkedHashMap<>();
        for (AiPromptReleaseEntity entity : mapper.selectAllCurrent()) {
            PromptReleaseRecord record = toRecord(entity);
            if (record.revision() > bundleRevision
                    || releases.putIfAbsent(record.promptKey(), record) != null) {
                throw new IllegalStateException("AI prompt release persistence state is inconsistent");
            }
        }
        return new PromptReleaseState(bundleRevision, releases);
    }

    @Override
    @Transactional
    public PromptReleaseRecord publish(PromptReleaseMutation mutation) {
        validateMutation(mutation);
        long bundleRevision = requireBundleRevision(mapper.lockBundleRevision());
        AiPromptReleaseEntity current = mapper.selectCurrentForUpdate(mutation.promptKey());
        long actualRevision = current == null || current.getRevision() == null
                ? 0L : current.getRevision();
        if (actualRevision > bundleRevision) {
            throw new IllegalStateException("AI prompt release head exceeds the bundle revision");
        }
        if (actualRevision != mutation.expectedRevision()) {
            throw new PromptReleaseConflictException(mutation.expectedRevision(), actualRevision);
        }

        long nextRevision = Math.addExact(bundleRevision, 1L);
        LocalDateTime timestamp = LocalDateTime.now(ZoneOffset.UTC);
        if (mapper.advanceBundle(bundleRevision, nextRevision, mutation.updatedBy(), timestamp) != 1) {
            throw new IllegalStateException("AI prompt release bundle could not advance atomically");
        }

        AiPromptReleaseEntity release = toEntity(mutation, nextRevision, timestamp, current);
        if (mapper.upsertCurrent(release) <= 0) {
            throw new IllegalStateException("AI prompt release pointer could not be persisted");
        }
        if (mapper.insertHistory(toHistoryEntity(mutation, nextRevision, timestamp)) != 1) {
            throw new IllegalStateException("AI prompt release history could not be persisted");
        }
        return toRecord(release);
    }

    @Override
    public Optional<PromptReleaseHistoryEntry> findHistory(String promptKey, long revision) {
        if (promptKey == null || promptKey.isBlank() || revision <= 0) {
            return Optional.empty();
        }
        return Optional.ofNullable(mapper.selectHistory(promptKey.trim(), revision))
                .map(this::toHistoryEntry);
    }

    @Override
    public List<PromptReleaseHistoryEntry> listHistory(String promptKey, int limit) {
        if (promptKey == null || promptKey.isBlank() || limit <= 0) {
            return List.of();
        }
        return mapper.selectHistoryPage(promptKey.trim(), limit).stream()
                .map(this::toHistoryEntry)
                .toList();
    }

    private AiPromptReleaseEntity toEntity(PromptReleaseMutation mutation,
                                            long revision,
                                            LocalDateTime timestamp,
                                            AiPromptReleaseEntity current) {
        PromptReleaseSpec release = mutation.release();
        return AiPromptReleaseEntity.builder()
                .promptKey(mutation.promptKey())
                .stableVersion(release.stableVersion())
                .canaryVersion(release.hasCanary() ? release.canaryVersion() : null)
                .canaryPercentage(release.canaryPercentage())
                .revision(revision)
                .updatedBy(mutation.updatedBy())
                .changeNote(mutation.changeNote())
                .createTime(current == null ? timestamp : current.getCreateTime())
                .updateTime(timestamp)
                .build();
    }

    private AiPromptReleaseHistoryEntity toHistoryEntity(PromptReleaseMutation mutation,
                                                          long revision,
                                                          LocalDateTime timestamp) {
        PromptReleaseSpec release = mutation.release();
        return AiPromptReleaseHistoryEntity.builder()
                .revision(revision)
                .promptKey(mutation.promptKey())
                .stableVersion(release.stableVersion())
                .canaryVersion(release.hasCanary() ? release.canaryVersion() : null)
                .canaryPercentage(release.canaryPercentage())
                .action(mutation.action().name())
                .sourceRevision(mutation.sourceRevision())
                .updatedBy(mutation.updatedBy())
                .changeNote(mutation.changeNote())
                .evidenceId(mutation.evidenceId().isBlank() ? null : mutation.evidenceId())
                .createTime(timestamp)
                .build();
    }

    private PromptReleaseRecord toRecord(AiPromptReleaseEntity entity) {
        if (entity == null || entity.getPromptKey() == null || entity.getPromptKey().isBlank()
                || entity.getStableVersion() == null || entity.getStableVersion().isBlank()
                || entity.getCanaryPercentage() == null || entity.getRevision() == null
                || entity.getUpdatedBy() == null || entity.getUpdateTime() == null) {
            throw new IllegalStateException("AI prompt release row is incomplete");
        }
        return new PromptReleaseRecord(
                entity.getPromptKey(),
                new PromptReleaseSpec(
                        entity.getStableVersion(),
                        entity.getCanaryVersion(),
                        entity.getCanaryPercentage()
                ),
                entity.getRevision(),
                entity.getUpdatedBy(),
                entity.getChangeNote(),
                toInstant(entity.getUpdateTime())
        );
    }

    private PromptReleaseHistoryEntry toHistoryEntry(AiPromptReleaseHistoryEntity entity) {
        if (entity == null || entity.getRevision() == null || entity.getPromptKey() == null
                || entity.getStableVersion() == null || entity.getCanaryPercentage() == null
                || entity.getAction() == null || entity.getUpdatedBy() == null
                || entity.getCreateTime() == null) {
            throw new IllegalStateException("AI prompt release history row is incomplete");
        }
        PromptReleaseAction action;
        try {
            action = PromptReleaseAction.valueOf(entity.getAction());
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("AI prompt release history action is invalid", exception);
        }
        return new PromptReleaseHistoryEntry(
                entity.getPromptKey(),
                new PromptReleaseSpec(
                        entity.getStableVersion(),
                        entity.getCanaryVersion(),
                        entity.getCanaryPercentage()
                ),
                entity.getRevision(),
                action,
                entity.getSourceRevision(),
                entity.getUpdatedBy(),
                entity.getChangeNote(),
                entity.getEvidenceId(),
                toInstant(entity.getCreateTime())
        );
    }

    private void validateMutation(PromptReleaseMutation mutation) {
        if (mutation == null || mutation.promptKey() == null
                || !KEY_PATTERN.matcher(mutation.promptKey()).matches()
                || mutation.release() == null || mutation.expectedRevision() < 0
                || mutation.updatedBy() <= 0 || mutation.changeNote() == null
                || mutation.changeNote().isBlank()
                || mutation.changeNote().length() > MAX_CHANGE_NOTE_LENGTH
                || mutation.action() == null) {
            throw new IllegalArgumentException("invalid AI prompt release mutation");
        }
        PromptReleaseSpec release = mutation.release();
        boolean stableValid = VERSION_PATTERN.matcher(release.stableVersion()).matches();
        boolean canaryValid = release.canaryPercentage() == 0
                ? release.canaryVersion().isBlank()
                : release.canaryPercentage() > 0
                && release.canaryPercentage() <= 100
                && VERSION_PATTERN.matcher(release.canaryVersion()).matches()
                && !release.stableVersion().equals(release.canaryVersion());
        if (!stableValid || !canaryValid) {
            throw new IllegalArgumentException("invalid AI prompt release pointers");
        }
        if ((mutation.action() == PromptReleaseAction.PUBLISH && mutation.sourceRevision() != null)
                || (mutation.action() == PromptReleaseAction.ROLLBACK
                && (mutation.sourceRevision() == null || mutation.sourceRevision() <= 0))) {
            throw new IllegalArgumentException("invalid AI prompt release audit source");
        }
        if ((mutation.action() == PromptReleaseAction.PUBLISH
                && !mutation.evidenceId().matches("[0-9a-fA-F-]{36}"))
                || (mutation.action() == PromptReleaseAction.ROLLBACK
                && !mutation.evidenceId().isBlank())) {
            throw new IllegalArgumentException("invalid AI prompt release evidence reference");
        }
    }

    private long requireBundleRevision(Long revision) {
        if (revision == null || revision < 0) {
            throw new IllegalStateException("AI prompt release bundle head is missing");
        }
        return revision;
    }

    private Instant toInstant(LocalDateTime value) {
        return value.toInstant(ZoneOffset.UTC);
    }
}
