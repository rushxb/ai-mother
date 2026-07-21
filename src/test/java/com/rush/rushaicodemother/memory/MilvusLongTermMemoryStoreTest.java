package com.rush.rushaicodemother.memory;

import com.rush.rushaicodemother.config.MilvusMemoryProperties;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import io.milvus.v2.service.vector.request.SearchReq;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MilvusLongTermMemoryStoreTest {

    @Test
    void searchMustUseCurrentLimitApiAndTenantBoundFilter() {
        MilvusClientV2 client = mock(MilvusClientV2.class);
        when(client.hasCollection(any(HasCollectionReq.class))).thenReturn(true);
        when(client.search(any(SearchReq.class))).thenReturn(null);
        MilvusMemoryProperties properties = new MilvusMemoryProperties();
        MemoryEmbeddingService embeddingService = mock(MemoryEmbeddingService.class);
        when(embeddingService.dimension()).thenReturn(2);
        MilvusLongTermMemoryStore store = new MilvusLongTermMemoryStore(
                client, properties, embeddingService);

        store.search(new SemanticMemoryQuery(
                11L, 7L, new float[]{0.1f, 0.2f},
                Set.of(MemoryType.TASK_OUTCOME), 6, 0.45));

        ArgumentCaptor<SearchReq> captor = ArgumentCaptor.forClass(SearchReq.class);
        verify(client).search(captor.capture());
        SearchReq request = captor.getValue();
        assertEquals(6L, request.getLimit());
        assertTrue(request.getFilter().contains("app_id == 11"));
        assertTrue(request.getFilter().contains("user_id == 7"));
        assertTrue(request.getFilter().contains("memory_type in"));
    }
}
