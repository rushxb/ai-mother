package com.rush.rushaicodemother.memory;

import cn.hutool.crypto.digest.DigestUtil;
import cn.hutool.json.JSONUtil;
import com.google.gson.JsonObject;
import com.rush.rushaicodemother.config.MilvusMemoryProperties;
import com.rush.rushaicodemother.monitor.SemanticMemoryMetricsCollector;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.request.UpsertReq;
import io.milvus.v2.service.vector.response.SearchResp;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MilvusLongTermMemoryStoreTest {

    @Test
    void searchMustUseCurrentLimitApiAndTenantBoundEmbeddingFilter() {
        Fixture fixture = fixture();
        when(fixture.client.search(any(SearchReq.class))).thenReturn(null);

        fixture.store.search(new SemanticMemoryQuery(
                5L, 11L, new float[]{0.1f, 0.2f},
                Set.of(MemoryType.TASK_OUTCOME), 6, 0.45));

        ArgumentCaptor<SearchReq> captor = ArgumentCaptor.forClass(SearchReq.class);
        verify(fixture.client).search(captor.capture());
        SearchReq request = captor.getValue();
        assertEquals(6L, request.getLimit());
        assertTrue(request.getFilter().contains("tenant_id == 5"));
        assertTrue(request.getFilter().contains("app_id == 11"));
        assertTrue(request.getFilter().contains("schema_version == 2"));
        assertTrue(request.getFilter().contains("embedding_model == \"test-model\""));
        assertTrue(request.getFilter().contains("embedding_version == \"v1\""));
        assertTrue(request.getFilter().contains("memory_type in"));
        assertEquals(1, fixture.metrics.get("semantic_memory_store_operations_total")
                .tags("operation", "search", "status", "success").counter().count());
    }

    @Test
    void upsertMustUseMilvusUpsertAndPersistTheVersionedEmbeddingContract() {
        Fixture fixture = fixture();
        String content = "build passed";
        SemanticMemory memory = new SemanticMemory(
                DigestUtil.sha256Hex("memory-1"), 5L, 11L, 7L, "task-1",
                MemoryType.TASK_OUTCOME, content,
                SemanticMemoryGovernancePolicy.governMetadata(
                        Map.of("route", "heavy", "unknown", "discarded"), content),
                new float[]{0.1f, 0.2f}, Instant.parse("2026-07-21T04:00:00Z"));

        fixture.store.upsert(memory);

        ArgumentCaptor<UpsertReq> captor = ArgumentCaptor.forClass(UpsertReq.class);
        verify(fixture.client).upsert(captor.capture());
        verify(fixture.collectionManager).ensureReady();
        JsonObject row = captor.getValue().getData().getFirst();
        assertEquals("test-model", row.get("embedding_model").getAsString());
        assertEquals("v1", row.get("embedding_version").getAsString());
        assertEquals(2, row.get("schema_version").getAsInt());
        assertEquals(5L, row.get("tenant_id").getAsLong());
        assertTrue(row.get("metadata").isJsonObject());
        verify(fixture.client, never()).insert(any());
    }

    @Test
    void invalidVectorMustFailBeforeMilvusOrCollectionInitialization() {
        Fixture fixture = fixture();
        String content = "bad vector";
        SemanticMemory memory = new SemanticMemory(
                DigestUtil.sha256Hex("memory-2"), 5L, 11L, 7L, "task-2",
                MemoryType.FAILURE_LESSON, content,
                SemanticMemoryGovernancePolicy.governMetadata(Map.of(), content),
                new float[]{Float.NaN, 0.2f}, Instant.now());

        assertThrows(IllegalArgumentException.class, () -> fixture.store.upsert(memory));

        verify(fixture.collectionManager, never()).ensureReady();
        verify(fixture.client, never()).upsert(any());
        assertEquals(1, fixture.metrics.get("semantic_memory_store_operations_total")
                .tags("operation", "upsert", "status", "error").counter().count());
    }

    @Test
    void malformedOrCrossTenantRowsMustBeSkippedWithoutFailingTheRecall() {
        Fixture fixture = fixture();
        String content = "safe memory";
        Map<String, Object> validEntity = entity(5L, 11L, content);
        Map<String, Object> crossTenantEntity = entity(99L, 11L, "cross tenant");
        SearchResp.SearchResult valid = SearchResp.SearchResult.builder()
                .id(DigestUtil.sha256Hex("valid"))
                .score(0.9f)
                .entity(validEntity)
                .build();
        SearchResp.SearchResult malformed = SearchResp.SearchResult.builder()
                .id(DigestUtil.sha256Hex("malformed"))
                .score(0.99f)
                .entity(crossTenantEntity)
                .build();
        when(fixture.client.search(any(SearchReq.class))).thenReturn(SearchResp.builder()
                .searchResults(List.of(List.of(valid, malformed)))
                .build());

        List<SemanticMemoryHit> hits = fixture.store.search(new SemanticMemoryQuery(
                5L, 11L, new float[]{0.1f, 0.2f}, Set.of(), 5, 0.5));

        assertEquals(1, hits.size());
        assertEquals(content, hits.getFirst().memory().content());
        assertEquals(5L, hits.getFirst().memory().tenantId());
        assertEquals(1, fixture.metrics.get("semantic_memory_malformed_rows_total")
                .counter().count());
    }

    private Map<String, Object> entity(Long tenantId, Long appId, String content) {
        return Map.ofEntries(
                Map.entry(MilvusMemorySchema.TENANT_ID, tenantId),
                Map.entry(MilvusMemorySchema.APP_ID, appId),
                Map.entry(MilvusMemorySchema.USER_ID, 7L),
                Map.entry(MilvusMemorySchema.TASK_ID, "task-1"),
                Map.entry(MilvusMemorySchema.MEMORY_TYPE, MemoryType.TASK_OUTCOME.name()),
                Map.entry(MilvusMemorySchema.CONTENT, content),
                Map.entry(MilvusMemorySchema.METADATA, JSONUtil.toJsonStr(
                        SemanticMemoryGovernancePolicy.governMetadata(Map.of(), content))),
                Map.entry(MilvusMemorySchema.EMBEDDING_MODEL, "test-model"),
                Map.entry(MilvusMemorySchema.EMBEDDING_VERSION, "v1"),
                Map.entry(MilvusMemorySchema.CREATED_AT, 1_753_070_400_000L),
                Map.entry(MilvusMemorySchema.ROW_SCHEMA_VERSION, 2)
        );
    }

    private Fixture fixture() {
        MilvusClientV2 client = mock(MilvusClientV2.class);
        MilvusMemoryProperties properties = new MilvusMemoryProperties();
        MemoryEmbeddingService embeddingService = mock(MemoryEmbeddingService.class);
        when(embeddingService.dimension()).thenReturn(2);
        when(embeddingService.modelId()).thenReturn("test-model");
        when(embeddingService.modelVersion()).thenReturn("v1");
        MilvusMemoryCollectionManager collectionManager = mock(MilvusMemoryCollectionManager.class);
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        return new Fixture(
                client,
                collectionManager,
                new MilvusLongTermMemoryStore(
                        client, properties, embeddingService, collectionManager,
                        new SemanticMemoryMetricsCollector(metrics)),
                metrics);
    }

    private record Fixture(
            MilvusClientV2 client,
            MilvusMemoryCollectionManager collectionManager,
            MilvusLongTermMemoryStore store,
            SimpleMeterRegistry metrics
    ) {
    }
}
