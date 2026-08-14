package com.rush.rushaicodemother.infrastructure.persistence.task;

import com.rush.rushaicodemother.mapper.GenerationTerminalEffectMapper;
import com.rush.rushaicodemother.mapper.projection.GenerationTerminalEffectBacklogRow;
import com.rush.rushaicodemother.model.entity.GenerationTask;
import com.rush.rushaicodemother.orchestration.finalization.GenerationTerminalEffectAdminItem;
import com.rush.rushaicodemother.orchestration.finalization.GenerationTerminalEffectBacklog;
import com.rush.rushaicodemother.orchestration.finalization.GenerationFinalizationCommand;
import com.rush.rushaicodemother.orchestration.finalization.GenerationFinalizationCommandCodec;
import com.rush.rushaicodemother.orchestration.finalization.GenerationTerminalEffect;
import com.rush.rushaicodemother.orchestration.finalization.GenerationTerminalEffectOperation;
import com.rush.rushaicodemother.orchestration.finalization.GenerationTerminalEffectRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
@Slf4j
public class MyBatisGenerationTerminalEffectRepository implements GenerationTerminalEffectRepository {

    private static final int MAX_BATCH_SIZE = 500;
    private static final int MAX_ADMIN_LIST_SIZE = 100;
    private static final int MAX_ERROR_LENGTH = 1024;
    private static final String TASK_ID_PATTERN = "[A-Za-z0-9_-]{1,128}";

    private final GenerationTerminalEffectMapper mapper;
    private final ZoneId databaseZone = ZoneId.systemDefault();

    @Override
    @Transactional
    public List<GenerationTerminalEffect> claimBatch(Instant now,
                                                      Instant leaseUntil,
                                                      String leaseOwner,
                                                      int limit,
                                                      int maxAttempts) {
        if (now == null || leaseUntil == null || !leaseUntil.isAfter(now)
                || leaseOwner == null || leaseOwner.isBlank()
                || limit <= 0 || limit > MAX_BATCH_SIZE || maxAttempts <= 0) {
            throw new IllegalArgumentException("终态副作用批次参数不合法");
        }
        List<GenerationTask> candidates = mapper.selectPending(toLocal(now), limit, maxAttempts);
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        List<GenerationTerminalEffect> claimed = new ArrayList<>();
        for (GenerationTask candidate : candidates) {
            int attempts = candidate.getTerminalEffectsAttempts() == null
                    ? 0 : candidate.getTerminalEffectsAttempts();
            long epoch = candidate.getTerminalIntentExecutionEpoch() == null
                    ? 0L : candidate.getTerminalIntentExecutionEpoch();
            if (epoch <= 0) {
                quarantineMalformed(candidate, epoch, attempts, maxAttempts, now,
                        "终态副作用缺少有效执行轮次");
                continue;
            }
            GenerationFinalizationCommand command;
            try {
                command = decode(candidate);
            } catch (RuntimeException malformed) {
                String reason = malformed instanceof IllegalStateException
                        && malformed.getMessage() != null && !malformed.getMessage().isBlank()
                        ? malformed.getMessage()
                        : malformed.getClass().getSimpleName();
                quarantineMalformed(candidate, epoch, attempts, maxAttempts, now,
                        "终态意图无法解码: " + reason);
                continue;
            }
            if (mapper.claim(candidate.getTaskId(), epoch, attempts, maxAttempts,
                    leaseOwner, toLocal(now), toLocal(leaseUntil)) != 1) {
                continue;
            }
            claimed.add(new GenerationTerminalEffect(
                    candidate.getTaskId(), candidate.getAppId(), candidate.getUserId(),
                    candidate.getRoute(), command, attempts + 1,
                    candidate.getTerminalEffectsCompletedMask() == null
                            ? 0L : candidate.getTerminalEffectsCompletedMask()));
        }
        return List.copyOf(claimed);
    }

    @Override
    public boolean markCompleted(String taskId,
                                 long executionEpoch,
                                 String leaseOwner,
                                 Instant completedAt) {
        return mapper.markCompleted(
                taskId, executionEpoch, leaseOwner,
                GenerationTerminalEffectOperation.requiredMask(), toLocal(completedAt)) == 1;
    }

    @Override
    public boolean markOperationCompleted(String taskId,
                                          long executionEpoch,
                                          String leaseOwner,
                                          GenerationTerminalEffectOperation operation,
                                          Instant completedAt) {
        if (operation == null || completedAt == null) {
            throw new IllegalArgumentException("终态副作用回执参数不合法");
        }
        return mapper.markOperationCompleted(
                taskId, executionEpoch, leaseOwner, operation.mask(), toLocal(completedAt)) == 1;
    }

    @Override
    public boolean markFailed(String taskId,
                              long executionEpoch,
                              String leaseOwner,
                              String error,
                              Instant failedAt,
                              Instant nextAttemptAt) {
        String normalized = error == null ? "未知错误" : error.trim();
        if (normalized.length() > MAX_ERROR_LENGTH) {
            normalized = normalized.substring(0, MAX_ERROR_LENGTH);
        }
        return mapper.markFailed(
                taskId, executionEpoch, leaseOwner, normalized, toLocal(nextAttemptAt)) == 1;
    }

    @Override
    public GenerationTerminalEffectBacklog inspectBacklog(Instant now, int maxAttempts) {
        if (now == null || maxAttempts <= 0) {
            throw new IllegalArgumentException("终态副作用积压查询参数不合法");
        }
        GenerationTerminalEffectBacklogRow row = mapper.inspectBacklog(toLocal(now), maxAttempts);
        if (row == null) {
            return GenerationTerminalEffectBacklog.empty();
        }
        return new GenerationTerminalEffectBacklog(
                count(row.getPending()),
                count(row.getRetrying()),
                count(row.getLeased()),
                count(row.getDeadLetter()),
                toInstant(row.getOldestPendingAt()));
    }

    @Override
    public List<GenerationTerminalEffectAdminItem> listOutstanding(Instant now,
                                                                    int maxAttempts,
                                                                    int limit) {
        if (now == null || maxAttempts <= 0 || limit <= 0 || limit > MAX_ADMIN_LIST_SIZE) {
            throw new IllegalArgumentException("终态副作用管理查询参数不合法");
        }
        List<GenerationTask> rows = mapper.selectOutstanding(toLocal(now), maxAttempts, limit);
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        return rows.stream()
                .filter(Objects::nonNull)
                .map(row -> toAdminItem(row, now, maxAttempts))
                .toList();
    }

    @Override
    @Transactional
    public boolean replayDeadLetter(String taskId,
                                    long executionEpoch,
                                    long operatorUserId,
                                    Instant requestedAt,
                                    int maxAttempts) {
        if (taskId == null || !taskId.matches(TASK_ID_PATTERN)
                || executionEpoch <= 0 || operatorUserId <= 0
                || requestedAt == null || maxAttempts <= 0) {
            throw new IllegalArgumentException("终态副作用重放参数不合法");
        }
        LocalDateTime requestedLocal = toLocal(requestedAt);
        Integer previousAttempts = mapper.selectReplayAttemptsForUpdate(
                taskId, executionEpoch, maxAttempts, requestedLocal);
        if (previousAttempts == null || previousAttempts < maxAttempts) {
            return false;
        }
        if (mapper.replayDeadLetter(
                taskId, executionEpoch, previousAttempts, requestedLocal) != 1) {
            return false;
        }
        if (mapper.insertReplayAudit(
                UUID.randomUUID().toString(), taskId, executionEpoch,
                previousAttempts, operatorUserId, requestedLocal) != 1) {
            throw new IllegalStateException("终态副作用重放审计写入失败");
        }
        return true;
    }

    private void quarantineMalformed(GenerationTask candidate,
                                     long executionEpoch,
                                     int expectedAttempts,
                                     int maxAttempts,
                                     Instant now,
                                     String error) {
        String taskId = candidate == null ? null : candidate.getTaskId();
        if (taskId == null || taskId.isBlank()) {
            log.error("发现缺少 taskId 的终态副作用坏记录，无法自动隔离");
            return;
        }
        String normalized = error == null ? "终态意图损坏" : error;
        if (normalized.length() > MAX_ERROR_LENGTH) {
            normalized = normalized.substring(0, MAX_ERROR_LENGTH);
        }
        boolean quarantined = mapper.markMalformed(
                taskId, executionEpoch, expectedAttempts, maxAttempts, normalized, toLocal(now)) == 1;
        if (quarantined) {
            log.error("已隔离无法处理的终态副作用，taskId: {}, executionEpoch: {}, error: {}",
                    taskId, executionEpoch, normalized);
        }
    }

    private GenerationFinalizationCommand decode(GenerationTask task) {
        if (!Integer.valueOf(GenerationFinalizationCommandCodec.CURRENT_SCHEMA_VERSION)
                .equals(task.getTerminalIntentSchemaVersion())
                || task.getTerminalIntentPayloadJson() == null) {
            throw new IllegalStateException("终态副作用缺少受支持的意图载荷");
        }
        GenerationFinalizationCommand command = GenerationFinalizationCommandCodec.fromJson(
                task.getTerminalIntentPayloadJson());
        long expectedEpoch = task.getTerminalIntentExecutionEpoch() == null
                ? 0L : task.getTerminalIntentExecutionEpoch();
        if (command == null
                || command.executionFence() == null
                || !Objects.equals(task.getTaskId(), command.taskId())
                || !Objects.equals(task.getAppId(), command.appId())
                || command.executionFence().executionEpoch() != expectedEpoch) {
            throw new IllegalStateException("终态副作用意图身份不一致");
        }
        return command;
    }

    private GenerationTerminalEffectAdminItem toAdminItem(GenerationTask row,
                                                            Instant now,
                                                            int maxAttempts) {
        int attempts = row.getTerminalEffectsAttempts() == null
                ? 0 : row.getTerminalEffectsAttempts();
        Instant leaseUntil = toInstant(row.getTerminalEffectsLeaseUntil());
        Instant nextAttemptAt = toInstant(row.getTerminalEffectsNextAttemptAt());
        String state;
        if (leaseUntil != null && !leaseUntil.isBefore(now)) {
            state = "leased";
        } else if (attempts >= maxAttempts) {
            state = "dead_letter";
        } else if (nextAttemptAt != null && nextAttemptAt.isAfter(now)) {
            state = "retrying";
        } else {
            state = "pending";
        }
        return new GenerationTerminalEffectAdminItem(
                row.getTaskId(), row.getAppId(), row.getRoute(),
                row.getTerminalIntentExecutionEpoch() == null
                        ? 0L : row.getTerminalIntentExecutionEpoch(),
                state, attempts, row.getTerminalEffectsError(), nextAttemptAt, leaseUntil,
                toInstant(row.getTerminalIntentFinalizedAt()));
    }

    private long count(Long value) {
        return value == null ? 0L : value;
    }

    private Instant toInstant(LocalDateTime value) {
        return value == null ? null : value.atZone(databaseZone).toInstant();
    }

    private LocalDateTime toLocal(Instant value) {
        if (value == null) {
            throw new IllegalArgumentException("终态副作用时间不能为空");
        }
        return LocalDateTime.ofInstant(value, databaseZone);
    }
}
