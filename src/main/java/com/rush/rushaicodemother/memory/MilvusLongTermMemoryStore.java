package com.rush.rushaicodemother.memory;

import cn.hutool.json.JSONUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rush.rushaicodemother.config.MilvusMemoryProperties;
import com.rush.rushaicodemother.infrastructure.diagnostic.LogExceptionSanitizer;
import com.rush.rushaicodemother.monitor.SemanticMemoryMetricsCollector;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.ConsistencyLevel;
import io.milvus.v2.service.vector.request.DeleteReq;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.request.UpsertReq;
import io.milvus.v2.service.vector.request.data.FloatVec;
import io.milvus.v2.service.vector.response.SearchResp;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Milvus gRPC 适配器，用于持久的、租户范围的语义内存。 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.memory.long-term", name = "enabled", havingValue = "true")
public class MilvusLongTermMemoryStore implements LongTermMemoryStore {

    private final MilvusClientV2 client;
    private final MilvusMemoryProperties properties;
    private final MemoryEmbeddingService embeddingService;
    private final MilvusMemoryCollectionManager collectionManager;
    private final SemanticMemoryMetricsCollector metrics;

    MilvusLongTermMemoryStore(MilvusClientV2 client,
                              MilvusMemoryProperties properties,
                              MemoryEmbeddingService embeddingService,
                              MilvusMemoryCollectionManager collectionManager) {
        this(client, properties, embeddingService, collectionManager,
                SemanticMemoryMetricsCollector.noOp());
    }

    @Autowired
    public MilvusLongTermMemoryStore(MilvusClientV2 client,
                                     MilvusMemoryProperties properties,
                                     MemoryEmbeddingService embeddingService,
                                     MilvusMemoryCollectionManager collectionManager,
                                     SemanticMemoryMetricsCollector metrics) {
        this.client = client;
        this.properties = properties;
        this.embeddingService = embeddingService;
        this.collectionManager = collectionManager;
        this.metrics = metrics;
    }

    @Override
    public void upsert(SemanticMemory memory) {
        long started = System.nanoTime();
        try {
            SemanticMemoryGovernancePolicy.validateMemory(memory, embeddingService.dimension());
            validateEmbeddingIdentity();
            collectionManager.ensureReady();

            JsonObject row = new JsonObject();
            row.addProperty(MilvusMemorySchema.ID, memory.id());
            row.addProperty(MilvusMemorySchema.TENANT_ID, memory.tenantId());
            row.addProperty(MilvusMemorySchema.APP_ID, memory.appId());
            row.addProperty(MilvusMemorySchema.USER_ID, memory.userId());
            row.addProperty(MilvusMemorySchema.TASK_ID, memory.taskId());
            row.addProperty(MilvusMemorySchema.MEMORY_TYPE, memory.type().name());
            row.addProperty(MilvusMemorySchema.CONTENT, memory.content());
            row.add(MilvusMemorySchema.METADATA,
                    JsonParser.parseString(JSONUtil.toJsonStr(memory.metadata())));
            row.addProperty(MilvusMemorySchema.EMBEDDING_MODEL, embeddingService.modelId());
            row.addProperty(MilvusMemorySchema.EMBEDDING_VERSION, embeddingService.modelVersion());
            row.addProperty(MilvusMemorySchema.CREATED_AT, memory.createdAt().toEpochMilli());
            row.addProperty(MilvusMemorySchema.ROW_SCHEMA_VERSION, MilvusMemorySchema.SCHEMA_VERSION);
            row.add(MilvusMemorySchema.VECTOR, vector(memory.embedding()));

            try {
                client.upsert(UpsertReq.builder()
                        .databaseName(properties.getDatabaseName())
                        .collectionName(properties.getCollectionName())
                        .data(List.of(row))
                        .build());
            } catch (RuntimeException failure) {
                collectionManager.invalidate();
                throw failure;
            }
            recordOperation("upsert", "success", started);
        } catch (RuntimeException failure) {
            recordOperation("upsert", "error", started);
            throw failure;
        }
    }

    @Override
    public List<SemanticMemoryHit> search(SemanticMemoryQuery query) {
        long started = System.nanoTime();
        try {
            SemanticMemoryGovernancePolicy.validateQuery(query, embeddingService.dimension());
            validateEmbeddingIdentity();
            collectionManager.ensureReady();

            SearchResp response;
            try {
                response = client.search(SearchReq.builder()
                        .databaseName(properties.getDatabaseName())
                        .collectionName(properties.getCollectionName())
                        .annsField(MilvusMemorySchema.VECTOR)
                        .metricType(MilvusMemorySchema.METRIC_TYPE)
                        .limit(query.topK())
                        .filter(filter(query))
                        .outputFields(MilvusMemorySchema.OUTPUT_FIELDS)
                        .data(List.of(new FloatVec(query.embedding())))
                        .consistencyLevel(ConsistencyLevel.STRONG)
                        .build());
            } catch (RuntimeException failure) {
                collectionManager.invalidate();
                throw failure;
            }
            if (response == null || response.getSearchResults() == null
                    || response.getSearchResults().isEmpty()
                    || response.getSearchResults().getFirst() == null) {
                recordOperation("search", "success", started);
                return List.of();
            }

            List<SemanticMemoryHit> hits = new ArrayList<>();
            int malformedRows = 0;
            for (SearchResp.SearchResult result : response.getSearchResults().getFirst()) {
                try {
                    SemanticMemoryHit hit = toHit(result, query);
                    if (hit.score() >= query.minimumScore()) {
                        hits.add(hit);
                    }
                } catch (RuntimeException malformedRow) {
                    malformedRows++;
                }
            }
            if (malformedRows > 0) {
                metrics.recordMalformedRows(malformedRows);
                log.warn("Skipped malformed Milvus semantic-memory rows, collection: {}, count: {}",
                        properties.getCollectionName(), malformedRows);
            }
            recordOperation("search", "success", started);
            return List.copyOf(hits);
        } catch (RuntimeException failure) {
            recordOperation("search", "error", started);
            throw failure;
        }
    }

    @Override
    public void deleteByApplication(Long tenantId, Long appId) {
        long started = System.nanoTime();
        try {
            requirePositive(tenantId, "tenantId");
            requirePositive(appId, "appId");
            collectionManager.ensureReady();
            try {
                client.delete(DeleteReq.builder()
                        .databaseName(properties.getDatabaseName())
                        .collectionName(properties.getCollectionName())
                        .filter(MilvusMemorySchema.TENANT_ID + " == " + tenantId
                                + " and " + MilvusMemorySchema.APP_ID + " == " + appId)
                        .build());
            } catch (RuntimeException failure) {
                collectionManager.invalidate();
                throw failure;
            }
            recordOperation("delete", "success", started);
        } catch (RuntimeException failure) {
            recordOperation("delete", "error", started);
            throw failure;
        }
    }

    private SemanticMemoryHit toHit(SearchResp.SearchResult result, SemanticMemoryQuery query) {
        if (result == null || result.getEntity() == null || result.getId() == null
                || result.getScore() == null || !Double.isFinite(result.getScore())) {
            throw new IllegalArgumentException("Milvus search row is incomplete");
        }
        Map<String, Object> entity = result.getEntity();
        Long tenantId = longValue(entity.get(MilvusMemorySchema.TENANT_ID));
        Long appId = longValue(entity.get(MilvusMemorySchema.APP_ID));
        if (!query.tenantId().equals(tenantId) || !query.appId().equals(appId)) {
            throw new IllegalArgumentException("Milvus search row crossed the tenant/application boundary");
        }
        int schemaVersion = integerValue(entity.get(MilvusMemorySchema.ROW_SCHEMA_VERSION));
        String embeddingModel = stringValue(entity.get(MilvusMemorySchema.EMBEDDING_MODEL));
        String embeddingVersion = stringValue(entity.get(MilvusMemorySchema.EMBEDDING_VERSION));
        if (schemaVersion != MilvusMemorySchema.SCHEMA_VERSION
                || !embeddingService.modelId().equals(embeddingModel)
                || !embeddingService.modelVersion().equals(embeddingVersion)) {
            throw new IllegalArgumentException("Milvus search row uses an incompatible embedding contract");
        }

        SemanticMemory memory = new SemanticMemory(
                String.valueOf(result.getId()),
                tenantId,
                appId,
                longValue(entity.get(MilvusMemorySchema.USER_ID)),
                stringValue(entity.get(MilvusMemorySchema.TASK_ID)),
                MemoryType.valueOf(stringValue(entity.get(MilvusMemorySchema.MEMORY_TYPE))),
                stringValue(entity.get(MilvusMemorySchema.CONTENT)),
                parseMetadata(entity.get(MilvusMemorySchema.METADATA)),
                new float[0],
                Instant.ofEpochMilli(longValue(entity.get(MilvusMemorySchema.CREATED_AT)))
        );
        SemanticMemoryGovernancePolicy.validateStoredMemory(memory);
        return new SemanticMemoryHit(memory, result.getScore());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseMetadata(Object value) {
        if (value == null) {
            return Map.of();
        }
        String json = value instanceof JsonElement element ? element.toString() : String.valueOf(value);
        Map<String, Object> metadata = JSONUtil.toBean(json, Map.class);
        if (metadata == null) {
            throw new IllegalArgumentException("Milvus metadata is empty");
        }
        SemanticMemoryGovernancePolicy.validateMetadata(metadata);
        return Map.copyOf(metadata);
    }

    private String filter(SemanticMemoryQuery query) {
        List<String> clauses = new ArrayList<>();
        clauses.add(MilvusMemorySchema.TENANT_ID + " == " + query.tenantId());
        clauses.add(MilvusMemorySchema.APP_ID + " == " + query.appId());
        clauses.add(MilvusMemorySchema.ROW_SCHEMA_VERSION + " == " + MilvusMemorySchema.SCHEMA_VERSION);
        clauses.add(MilvusMemorySchema.EMBEDDING_MODEL + " == " + literal(embeddingService.modelId()));
        clauses.add(MilvusMemorySchema.EMBEDDING_VERSION + " == " + literal(embeddingService.modelVersion()));
        Set<MemoryType> types = query.types();
        if (!types.isEmpty()) {
            String typeValues = types.stream()
                    .map(type -> literal(type.name()))
                    .collect(Collectors.joining(", "));
            clauses.add(MilvusMemorySchema.MEMORY_TYPE + " in [" + typeValues + "]");
        }
        return String.join(" and ", clauses);
    }

    private String literal(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private JsonArray vector(float[] embedding) {
        JsonArray array = new JsonArray();
        for (float value : embedding) {
            array.add(value);
        }
        return array;
    }

    private void validateEmbeddingIdentity() {
        if (embeddingService.modelId() == null || embeddingService.modelId().isBlank()
                || embeddingService.modelId().length() > 128
                || embeddingService.modelVersion() == null || embeddingService.modelVersion().isBlank()
                || embeddingService.modelVersion().length() > 64) {
            throw new IllegalStateException("semantic-memory embedding identity is invalid");
        }
    }

    private Long longValue(Object value) {
        return value instanceof Number number ? number.longValue() : Long.parseLong(String.valueOf(value));
    }

    private int integerValue(Object value) {
        return value instanceof Number number ? number.intValue() : Integer.parseInt(String.valueOf(value));
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private void requirePositive(Long value, String field) {
        if (value == null || value <= 0) {
            throw new IllegalArgumentException(field + " must be positive");
        }
    }

    private void recordOperation(String operation, String status, long startedNanos) {
        metrics.recordStoreOperation(operation, status,
                Duration.ofNanos(Math.max(0, System.nanoTime() - startedNanos)));
    }
}
