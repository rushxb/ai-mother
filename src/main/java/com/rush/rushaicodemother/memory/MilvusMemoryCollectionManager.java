package com.rush.rushaicodemother.memory;

import com.rush.rushaicodemother.config.MilvusMemoryProperties;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.ConsistencyLevel;
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
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Creates and periodically revalidates the versioned Milvus memory collection. */
@Component
@ConditionalOnProperty(prefix = "app.memory.long-term", name = "enabled", havingValue = "true")
public class MilvusMemoryCollectionManager {

    private final MilvusClientV2 client;
    private final MilvusMemoryProperties properties;
    private final MemoryEmbeddingService embeddingService;
    private final Object monitor = new Object();
    private volatile long lastVerifiedNanos = Long.MIN_VALUE;
    private volatile boolean ready;

    public MilvusMemoryCollectionManager(MilvusClientV2 client,
                                         MilvusMemoryProperties properties,
                                         MemoryEmbeddingService embeddingService) {
        this.client = client;
        this.properties = properties;
        this.embeddingService = embeddingService;
    }

    public void ensureReady() {
        long now = System.nanoTime();
        if (fresh(now)) {
            return;
        }
        synchronized (monitor) {
            now = System.nanoTime();
            if (fresh(now)) {
                return;
            }
            try {
                ensureCollectionExists();
                DescribeCollectionResp description = client.describeCollection(
                        DescribeCollectionReq.builder()
                                .databaseName(properties.getDatabaseName())
                                .collectionName(properties.getCollectionName())
                                .build());
                MilvusMemorySchema.validateCollection(description, embeddingService.dimension());
                ensureIndexReady();
                ensureLoaded();
                ready = true;
                lastVerifiedNanos = now;
            } catch (RuntimeException failure) {
                invalidate();
                throw failure;
            }
        }
    }

    public void invalidate() {
        ready = false;
        lastVerifiedNanos = Long.MIN_VALUE;
    }

    public Map<String, Object> readinessDetails() {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("collection", properties.getCollectionName());
        details.put("schemaVersion", MilvusMemorySchema.SCHEMA_VERSION);
        details.put("dimension", embeddingService.dimension());
        details.put("embeddingModel", embeddingService.modelId());
        details.put("embeddingVersion", embeddingService.modelVersion());
        details.put("indexType", MilvusMemorySchema.INDEX_TYPE.name());
        details.put("metricType", MilvusMemorySchema.METRIC_TYPE.name());
        details.put("ready", ready);
        return Map.copyOf(details);
    }

    private boolean fresh(long now) {
        long last = lastVerifiedNanos;
        return ready && last != Long.MIN_VALUE
                && now - last < properties.getReadinessRefreshInterval().toNanos();
    }

    private void ensureCollectionExists() {
        if (hasCollection()) {
            return;
        }
        try {
            client.createCollection(CreateCollectionReq.builder()
                    .databaseName(properties.getDatabaseName())
                    .collectionName(properties.getCollectionName())
                    .description("AI semantic memory schema v" + MilvusMemorySchema.SCHEMA_VERSION)
                    .collectionSchema(MilvusMemorySchema.createSchema(embeddingService.dimension()))
                    .indexParams(List.of(MilvusMemorySchema.indexParam()))
                    .consistencyLevel(ConsistencyLevel.STRONG)
                    .build());
        } catch (RuntimeException creationFailure) {
            if (!hasCollection()) {
                throw creationFailure;
            }
        }
    }

    private void ensureIndexReady() {
        List<String> indexes = client.listIndexes(ListIndexesReq.builder()
                .databaseName(properties.getDatabaseName())
                .collectionName(properties.getCollectionName())
                .fieldName(MilvusMemorySchema.VECTOR)
                .build());
        if (indexes == null || indexes.isEmpty()) {
            try {
                client.createIndex(CreateIndexReq.builder()
                        .databaseName(properties.getDatabaseName())
                        .collectionName(properties.getCollectionName())
                        .indexParams(List.of(MilvusMemorySchema.indexParam()))
                        .sync(true)
                        .timeout(properties.getReadinessTimeout().toMillis())
                        .build());
            } catch (RuntimeException creationFailure) {
                List<String> racedIndexes = client.listIndexes(ListIndexesReq.builder()
                        .databaseName(properties.getDatabaseName())
                        .collectionName(properties.getCollectionName())
                        .fieldName(MilvusMemorySchema.VECTOR)
                        .build());
                if (racedIndexes == null || racedIndexes.isEmpty()) {
                    throw creationFailure;
                }
            }
        }
        DescribeIndexResp description = client.describeIndex(DescribeIndexReq.builder()
                .databaseName(properties.getDatabaseName())
                .collectionName(properties.getCollectionName())
                .fieldName(MilvusMemorySchema.VECTOR)
                .build());
        MilvusMemorySchema.validateIndex(description);
    }

    private void ensureLoaded() {
        GetLoadStateReq stateRequest = GetLoadStateReq.builder()
                .databaseName(properties.getDatabaseName())
                .collectionName(properties.getCollectionName())
                .build();
        if (!Boolean.TRUE.equals(client.getLoadState(stateRequest))) {
            client.loadCollection(LoadCollectionReq.builder()
                    .databaseName(properties.getDatabaseName())
                    .collectionName(properties.getCollectionName())
                    .sync(true)
                    .timeout(properties.getReadinessTimeout().toMillis())
                    .build());
        }
        if (!Boolean.TRUE.equals(client.getLoadState(stateRequest))) {
            throw new IllegalStateException("Milvus memory collection is not loaded");
        }
    }

    private boolean hasCollection() {
        return Boolean.TRUE.equals(client.hasCollection(HasCollectionReq.builder()
                .databaseName(properties.getDatabaseName())
                .collectionName(properties.getCollectionName())
                .build()));
    }
}
