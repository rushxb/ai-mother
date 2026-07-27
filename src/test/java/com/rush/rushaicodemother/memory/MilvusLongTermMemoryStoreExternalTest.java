package com.rush.rushaicodemother.memory;

import com.rush.rushaicodemother.config.MilvusMemoryProperties;
import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.collection.request.DropCollectionReq;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import cn.hutool.crypto.digest.DigestUtil;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("external")
class MilvusLongTermMemoryStoreExternalTest {

    @Test
    void memoryMustRoundTripThroughConfiguredMilvus() {
        String uri = System.getProperty("milvusUri");
        Assumptions.assumeTrue(uri != null && !uri.isBlank(), "-DmilvusUri is required");
        String collection = "generation_memory_it_" + System.currentTimeMillis();
        MilvusMemoryProperties properties = new MilvusMemoryProperties();
        properties.setUri(uri);
        properties.setCollectionName(collection);
        MilvusClientV2 client = new MilvusClientV2(ConnectConfig.builder()
                .uri(properties.getUri())
                .dbName(properties.getDatabaseName())
                .connectTimeoutMs(3_000)
                .rpcDeadlineMs(10_000)
                .enablePrecheck(false)
                .build());
        try {
            MemoryEmbeddingService embeddingService = new BgeMemoryEmbeddingService();
            MilvusMemoryCollectionManager collectionManager = new MilvusMemoryCollectionManager(
                    client, properties, embeddingService);
            MilvusLongTermMemoryStore store = new MilvusLongTermMemoryStore(
                    client, properties, embeddingService, collectionManager);
            String content = "用户偏好：后台页面使用紧凑表格与蓝色主题";
            float[] embedding = embeddingService.embed(content);
            store.upsert(new SemanticMemory(
                    DigestUtil.sha256Hex(UUID.randomUUID().toString()),
                    990L, 991L, 992L, "task-it",
                    MemoryType.USER_PREFERENCE, content,
                    SemanticMemoryGovernancePolicy.governMetadata(
                            Map.of("source", "integration"), content),
                    embedding, Instant.now()
            ));

            List<SemanticMemoryHit> hits = store.search(new SemanticMemoryQuery(
                    990L, 991L, embedding, Set.of(MemoryType.USER_PREFERENCE), 3, 0.5));

            assertFalse(hits.isEmpty());
            assertEquals(content, hits.getFirst().memory().content());

            store.deleteByApplication(990L, 991L);
            List<SemanticMemoryHit> deletedHits = store.search(new SemanticMemoryQuery(
                    990L, 991L, embedding, Set.of(MemoryType.USER_PREFERENCE), 3, 0.5));
            assertTrue(deletedHits.isEmpty());
        } finally {
            if (Boolean.TRUE.equals(client.hasCollection(HasCollectionReq.builder()
                    .databaseName(properties.getDatabaseName())
                    .collectionName(collection)
                    .build()))) {
                client.dropCollection(DropCollectionReq.builder()
                        .databaseName(properties.getDatabaseName())
                        .collectionName(collection)
                        .build());
            }
            client.close();
        }
    }
}
