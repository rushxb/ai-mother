package com.rush.rushaicodemother.memory;

import java.util.List;

/** 应用程序范围的语义长期记忆的存储端口。 */
public interface LongTermMemoryStore {
    void upsert(SemanticMemory memory);
    List<SemanticMemoryHit> search(SemanticMemoryQuery query);
    void deleteByApplication(Long tenantId, Long appId);
}
