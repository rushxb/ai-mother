package com.rush.rushaicodemother.service.trace;

import com.rush.rushaicodemother.config.AiModelInvocationLedgerProperties;
import com.rush.rushaicodemother.monitor.AiModelMetricsCollector;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 恢复因进程崩溃或终态回调失败而遗留的 STARTED 物理调用账本。
 *
 * <p>生成调用通过任务终态或过期执行租约判定可恢复，不以“任务已终态”为唯一
 * 前提。恢复保留调用前持久化的保守 token 事实，并且只做 STARTED -&gt; ERROR
 * 单向 CAS，不会覆盖 provider 已完成的终态。</p>
 */
@Slf4j
@Service
public class GenerationModelInvocationRecoveryService {

    private final GenerationTracePersistenceService persistenceService;
    private final AiModelInvocationLedgerProperties properties;
    private final AiModelMetricsCollector metricsCollector;
    private final Clock clock;

    @Autowired
    public GenerationModelInvocationRecoveryService(
            GenerationTracePersistenceService persistenceService,
            AiModelInvocationLedgerProperties properties,
            AiModelMetricsCollector metricsCollector) {
        this(persistenceService, properties, metricsCollector, Clock.systemDefaultZone());
    }

    GenerationModelInvocationRecoveryService(
            GenerationTracePersistenceService persistenceService,
            AiModelInvocationLedgerProperties properties,
            AiModelMetricsCollector metricsCollector,
            Clock clock) {
        this.persistenceService = Objects.requireNonNull(persistenceService, "persistenceService");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.metricsCollector = Objects.requireNonNull(metricsCollector, "metricsCollector");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public int recoverStaleInvocations() {
        java.time.Instant observedInstant = clock.instant();
        LocalDateTime observedAt = LocalDateTime.ofInstant(observedInstant, clock.getZone());
        LocalDateTime cutoff = LocalDateTime.ofInstant(
                observedInstant.minus(properties.getRecoveryGrace()), clock.getZone());
        int generationCalls = persistenceService.recoverStaleGenerationStartedModelCalls(
                cutoff, observedAt);
        int exemptCalls = persistenceService.recoverStaleExemptStartedModelCalls(cutoff);
        int recovered = Math.addExact(generationCalls, exemptCalls);
        if (recovered > 0) {
            metricsCollector.recordInvocationRecovery("success", recovered);
            log.warn("Recovered stale physical model invocation ledgers, generationCount={}, exemptCount={}",
                    generationCalls, exemptCalls);
        }
        return recovered;
    }

    /** 从持久化账本刷新未结算调用 gauge，避免进程重启导致内存计数漂移。 */
    public long refreshUnsettledInvocationCount() {
        long count = persistenceService.countStartedModelCalls();
        metricsCollector.recordUnsettledInvocationCount(count);
        return count;
    }

}
