package com.rush.rushaicodemother.memory;

import com.rush.rushaicodemother.config.MilvusMemoryProperties;
import com.rush.rushaicodemother.monitor.SemanticMemoryMetricsCollector;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.ConsistencyLevel;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexBuildState;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.request.DescribeCollectionReq;
import io.milvus.v2.service.collection.request.GetLoadStateReq;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import io.milvus.v2.service.collection.request.LoadCollectionReq;
import io.milvus.v2.service.collection.response.DescribeCollectionResp;
import io.milvus.v2.service.index.request.CreateIndexReq;
import io.milvus.v2.service.index.request.DescribeIndexReq;
import io.milvus.v2.service.index.request.ListIndexesReq;
import io.milvus.v2.service.index.response.DescribeIndexResp;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MilvusMemoryCollectionManagerTest {

    @Test
    void missingCollectionMustBeCreatedWithExplicitSchemaIndexAndSynchronousLoad() {
        Fixture fixture = fixture();
        when(fixture.client.hasCollection(any(HasCollectionReq.class))).thenReturn(false);
        when(fixture.client.describeCollection(any(DescribeCollectionReq.class)))
                .thenReturn(validCollection(2));
        when(fixture.client.listIndexes(any(ListIndexesReq.class))).thenReturn(List.of());
        when(fixture.client.describeIndex(any(DescribeIndexReq.class))).thenReturn(validIndex());
        when(fixture.client.getLoadState(any(GetLoadStateReq.class))).thenReturn(false, true);

        fixture.manager.ensureReady();
        fixture.manager.ensureReady();

        ArgumentCaptor<CreateCollectionReq> collectionCaptor =
                ArgumentCaptor.forClass(CreateCollectionReq.class);
        verify(fixture.client).createCollection(collectionCaptor.capture());
        CreateCollectionReq request = collectionCaptor.getValue();
        assertFalse(request.getCollectionSchema().isEnableDynamicField());
        assertEquals(13, request.getCollectionSchema().getFieldSchemaList().size());
        assertEquals(DataType.JSON,
                request.getCollectionSchema().getField(MilvusMemorySchema.METADATA).getDataType());
        assertEquals(DataType.Int64,
                request.getCollectionSchema().getField(MilvusMemorySchema.TENANT_ID).getDataType());
        assertEquals(2,
                request.getCollectionSchema().getField(MilvusMemorySchema.VECTOR).getDimension());
        assertEquals(MilvusMemorySchema.INDEX_NAME,
                request.getIndexParams().getFirst().getIndexName());
        verify(fixture.client).createIndex(any(CreateIndexReq.class));
        verify(fixture.client).loadCollection(any(LoadCollectionReq.class));
        verify(fixture.client, times(1)).describeCollection(any(DescribeCollectionReq.class));
        assertTrue((Boolean) fixture.manager.readinessDetails().get("ready"));
        assertEquals(1, fixture.metrics.get("semantic_memory_readiness_checks_total")
                .tag("status", "ready").counter().count());
    }

    @Test
    void incompatibleExistingDimensionMustFailClosedBeforeIndexOrLoad() {
        Fixture fixture = fixture();
        when(fixture.client.hasCollection(any(HasCollectionReq.class))).thenReturn(true);
        when(fixture.client.describeCollection(any(DescribeCollectionReq.class)))
                .thenReturn(validCollection(3));

        IllegalStateException failure = assertThrows(
                IllegalStateException.class, fixture.manager::ensureReady);

        assertTrue(failure.getMessage().contains("schema v2"));
        assertFalse((Boolean) fixture.manager.readinessDetails().get("ready"));
        assertEquals(1, fixture.metrics.get("semantic_memory_readiness_checks_total")
                .tag("status", "failure").counter().count());
    }

    @Test
    void incompatibleMetricMustFailClosedInsteadOfSilentlyReusingTheIndex() {
        Fixture fixture = fixture();
        when(fixture.client.hasCollection(any(HasCollectionReq.class))).thenReturn(true);
        when(fixture.client.describeCollection(any(DescribeCollectionReq.class)))
                .thenReturn(validCollection(2));
        when(fixture.client.listIndexes(any(ListIndexesReq.class)))
                .thenReturn(List.of(MilvusMemorySchema.INDEX_NAME));
        DescribeIndexResp incompatible = DescribeIndexResp.builder()
                .indexDescriptions(List.of(DescribeIndexResp.IndexDesc.builder()
                        .fieldName(MilvusMemorySchema.VECTOR)
                        .indexName(MilvusMemorySchema.INDEX_NAME)
                        .indexType(MilvusMemorySchema.INDEX_TYPE)
                        .metricType(IndexParam.MetricType.L2)
                        .indexState(IndexBuildState.Finished)
                        .build()))
                .build();
        when(fixture.client.describeIndex(any(DescribeIndexReq.class))).thenReturn(incompatible);

        IllegalStateException failure = assertThrows(
                IllegalStateException.class, fixture.manager::ensureReady);

        assertTrue(failure.getMessage().contains("index contract"));
    }

    private Fixture fixture() {
        MilvusClientV2 client = mock(MilvusClientV2.class);
        MilvusMemoryProperties properties = new MilvusMemoryProperties();
        MemoryEmbeddingService embeddingService = mock(MemoryEmbeddingService.class);
        when(embeddingService.dimension()).thenReturn(2);
        when(embeddingService.modelId()).thenReturn("test-model");
        when(embeddingService.modelVersion()).thenReturn("v1");
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        return new Fixture(client,
                new MilvusMemoryCollectionManager(
                        client, properties, embeddingService,
                        new SemanticMemoryMetricsCollector(metrics)),
                metrics);
    }

    private DescribeCollectionResp validCollection(int dimension) {
        return DescribeCollectionResp.builder()
                .collectionName("generation_memory_v2")
                .databaseName("default")
                .primaryFieldName(MilvusMemorySchema.ID)
                .enableDynamicField(false)
                .autoID(false)
                .consistencyLevel(ConsistencyLevel.STRONG)
                .collectionSchema(MilvusMemorySchema.createSchema(dimension))
                .build();
    }

    private DescribeIndexResp validIndex() {
        return DescribeIndexResp.builder()
                .indexDescriptions(List.of(DescribeIndexResp.IndexDesc.builder()
                        .fieldName(MilvusMemorySchema.VECTOR)
                        .indexName(MilvusMemorySchema.INDEX_NAME)
                        .indexType(MilvusMemorySchema.INDEX_TYPE)
                        .metricType(MilvusMemorySchema.METRIC_TYPE)
                        .indexState(IndexBuildState.Finished)
                        .build()))
                .build();
    }

    private record Fixture(MilvusClientV2 client,
                           MilvusMemoryCollectionManager manager,
                           SimpleMeterRegistry metrics) {
    }
}
