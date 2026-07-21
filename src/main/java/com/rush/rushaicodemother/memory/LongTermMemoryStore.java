package com.rush.rushaicodemother.memory;

import java.util.List;

/** Storage port for application-scoped semantic long-term memory. */
public interface LongTermMemoryStore {
    void upsert(SemanticMemory memory);
    List<SemanticMemoryHit> search(SemanticMemoryQuery query);
    void deleteByApplication(Long tenantId, Long appId);
}
