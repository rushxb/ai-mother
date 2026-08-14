package com.rush.rushaicodemother.orchestration.finalization;

import com.rush.rushaicodemother.config.GenerationTerminalEffectProperties;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.monitor.GenerationTerminalEffectMetricsCollector;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/** 终态副作用的只读运维与精确 dead-letter 重放入口。 */
@Service
public class GenerationTerminalEffectManagementService {

    private static final int MAX_LIST_SIZE = 100;

    private final GenerationTerminalEffectRepository repository;
    private final GenerationTerminalEffectProperties properties;
    private final GenerationTerminalEffectMetricsCollector metrics;
    private final Clock clock;

    @Autowired
    public GenerationTerminalEffectManagementService(
            GenerationTerminalEffectRepository repository,
            GenerationTerminalEffectProperties properties,
            GenerationTerminalEffectMetricsCollector metrics) {
        this(repository, properties, metrics, Clock.systemUTC());
    }

    GenerationTerminalEffectManagementService(
            GenerationTerminalEffectRepository repository,
            GenerationTerminalEffectProperties properties,
            GenerationTerminalEffectMetricsCollector metrics,
            Clock clock) {
        this.repository = repository;
        this.properties = properties;
        this.metrics = metrics;
        this.clock = clock;
    }

    public Snapshot inspect() {
        Instant observedAt = clock.instant();
        try {
            GenerationTerminalEffectBacklog backlog = repository.inspectBacklog(
                    observedAt, properties.getMaxAttempts());
            metrics.updateBacklog(backlog, observedAt);
            metrics.recordBacklogRefresh("success");
            return new Snapshot(backlog, observedAt);
        } catch (RuntimeException failure) {
            metrics.recordBacklogRefresh("error");
            throw failure;
        }
    }

    public List<GenerationTerminalEffectAdminItem> listOutstanding(int limit) {
        if (limit <= 0 || limit > MAX_LIST_SIZE) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "终态副作用查询条数必须在 1 到 100 之间");
        }
        return repository.listOutstanding(clock.instant(), properties.getMaxAttempts(), limit);
    }

    public ReplayResult replayDeadLetter(String taskId,
                                         long executionEpoch,
                                         long operatorUserId) {
        Instant requestedAt = clock.instant();
        boolean replayed = repository.replayDeadLetter(
                taskId, executionEpoch, operatorUserId, requestedAt,
                properties.getMaxAttempts());
        if (!replayed) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR,
                    "不存在可重放的终态死信，或该执行轮次已被其他实例处理");
        }
        metrics.recordItems("replayed", 1);
        return new ReplayResult(taskId, executionEpoch, requestedAt);
    }

    public record Snapshot(GenerationTerminalEffectBacklog backlog, Instant observedAt) {
    }

    public record ReplayResult(String taskId, long executionEpoch, Instant requestedAt) {
    }
}
