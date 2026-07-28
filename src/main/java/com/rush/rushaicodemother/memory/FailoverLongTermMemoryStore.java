package com.rush.rushaicodemother.memory;

import com.rush.rushaicodemother.infrastructure.diagnostic.LogExceptionSanitizer;
import com.rush.rushaicodemother.monitor.SemanticMemoryMetricsCollector;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/** 写入有界本地副本，并在 Milvus 失败时回退到该副本。 */
@Slf4j
public class FailoverLongTermMemoryStore implements LongTermMemoryStore {
    private final LongTermMemoryStore primary;
    private final LongTermMemoryStore fallback;
    private final SemanticMemoryMetricsCollector metrics;

    public FailoverLongTermMemoryStore(LongTermMemoryStore primary, LongTermMemoryStore fallback) {
        this(primary, fallback, SemanticMemoryMetricsCollector.noOp());
    }

    public FailoverLongTermMemoryStore(LongTermMemoryStore primary,
                                       LongTermMemoryStore fallback,
                                       SemanticMemoryMetricsCollector metrics) {
        this.primary = primary;
        this.fallback = fallback;
        this.metrics = metrics;
    }

    /**
 * 新增或更新故障转移{@code Long}{@code Term}记忆存储。
 *
 * @param memory 记忆
 */
    @Override
    public void upsert(SemanticMemory memory) {
        fallback.upsert(memory);
        try {
            primary.upsert(memory);
        } catch (RuntimeException failure) {
            metrics.recordFailover("upsert");
            log.warn("Milvus long-term memory write failed; local fallback retained the record: {}",
                    LogExceptionSanitizer.sanitizeMessage(failure));
            throw failure;
        }
    }

    /**
 * 搜索匹配的故障转移{@code Long}{@code Term}记忆存储。
 *
 * @param query 查询
 * @return 故障转移{@code Long}{@code Term}记忆存储集合
 */
    @Override
    public List<SemanticMemoryHit> search(SemanticMemoryQuery query) {
        try {
            return primary.search(query);
        } catch (RuntimeException failure) {
            metrics.recordFailover("search");
            log.warn("Milvus long-term memory search failed; using local fallback: {}",
                    LogExceptionSanitizer.sanitizeMessage(failure));
            return fallback.search(query);
        }
    }

    /**
 * 删除按应用。
 *
 * @param tenantId 租户编号
 * @param appId 应用编号
 */
    @Override
    public void deleteByApplication(Long tenantId, Long appId) {
        RuntimeException primaryFailure = null;
        try {
            primary.deleteByApplication(tenantId, appId);
        } catch (RuntimeException failure) {
            metrics.recordFailover("delete");
            primaryFailure = failure;
        }
        fallback.deleteByApplication(tenantId, appId);
        if (primaryFailure != null) {
            throw primaryFailure;
        }
    }
}
