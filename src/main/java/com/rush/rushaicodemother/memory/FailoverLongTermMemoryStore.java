package com.rush.rushaicodemother.memory;

import com.rush.rushaicodemother.infrastructure.diagnostic.LogExceptionSanitizer;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/** Writes through to a bounded local copy and falls back to it on Milvus failures. */
@Slf4j
public class FailoverLongTermMemoryStore implements LongTermMemoryStore {
    private final LongTermMemoryStore primary;
    private final LongTermMemoryStore fallback;

    public FailoverLongTermMemoryStore(LongTermMemoryStore primary, LongTermMemoryStore fallback) {
        this.primary = primary;
        this.fallback = fallback;
    }

    @Override
    public void upsert(SemanticMemory memory) {
        fallback.upsert(memory);
        try {
            primary.upsert(memory);
        } catch (RuntimeException failure) {
            log.warn("Milvus long-term memory write failed; local fallback retained the record: {}",
                    LogExceptionSanitizer.sanitizeMessage(failure));
            throw failure;
        }
    }

    @Override
    public List<SemanticMemoryHit> search(SemanticMemoryQuery query) {
        try {
            return primary.search(query);
        } catch (RuntimeException failure) {
            log.warn("Milvus long-term memory search failed; using local fallback: {}",
                    LogExceptionSanitizer.sanitizeMessage(failure));
            return fallback.search(query);
        }
    }

    @Override
    public void deleteByApplication(Long tenantId, Long appId) {
        RuntimeException primaryFailure = null;
        try {
            primary.deleteByApplication(tenantId, appId);
        } catch (RuntimeException failure) {
            primaryFailure = failure;
        }
        fallback.deleteByApplication(tenantId, appId);
        if (primaryFailure != null) {
            throw primaryFailure;
        }
    }
}
