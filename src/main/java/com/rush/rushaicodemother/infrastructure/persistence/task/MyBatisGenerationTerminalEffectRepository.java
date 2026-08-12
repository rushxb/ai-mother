package com.rush.rushaicodemother.infrastructure.persistence.task;

import com.rush.rushaicodemother.mapper.GenerationTerminalEffectMapper;
import com.rush.rushaicodemother.model.entity.GenerationTask;
import com.rush.rushaicodemother.orchestration.finalization.GenerationFinalizationCommand;
import com.rush.rushaicodemother.orchestration.finalization.GenerationFinalizationCommandCodec;
import com.rush.rushaicodemother.orchestration.finalization.GenerationTerminalEffect;
import com.rush.rushaicodemother.orchestration.finalization.GenerationTerminalEffectRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

@Repository
@RequiredArgsConstructor
public class MyBatisGenerationTerminalEffectRepository implements GenerationTerminalEffectRepository {

    private static final int MAX_BATCH_SIZE = 500;
    private static final int MAX_ERROR_LENGTH = 1024;

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
            if (epoch <= 0 || mapper.claim(candidate.getTaskId(), epoch, attempts, maxAttempts,
                    leaseOwner, toLocal(now), toLocal(leaseUntil)) != 1) {
                continue;
            }
            GenerationFinalizationCommand command = decode(candidate);
            claimed.add(new GenerationTerminalEffect(
                    candidate.getTaskId(), candidate.getAppId(), candidate.getUserId(),
                    candidate.getRoute(), command, attempts + 1));
        }
        return List.copyOf(claimed);
    }

    @Override
    public boolean markCompleted(String taskId,
                                 long executionEpoch,
                                 String leaseOwner,
                                 Instant completedAt) {
        return mapper.markCompleted(
                taskId, executionEpoch, leaseOwner, toLocal(completedAt)) == 1;
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

    private GenerationFinalizationCommand decode(GenerationTask task) {
        if (!Integer.valueOf(GenerationFinalizationCommandCodec.CURRENT_SCHEMA_VERSION)
                .equals(task.getTerminalIntentSchemaVersion())
                || task.getTerminalIntentPayloadJson() == null) {
            throw new IllegalStateException("终态副作用缺少受支持的意图载荷");
        }
        return GenerationFinalizationCommandCodec.fromJson(task.getTerminalIntentPayloadJson());
    }

    private LocalDateTime toLocal(Instant value) {
        if (value == null) {
            throw new IllegalArgumentException("终态副作用时间不能为空");
        }
        return LocalDateTime.ofInstant(value, databaseZone);
    }
}
