package com.rush.rushaicodemother.memory;

import io.milvus.v2.common.ConsistencyLevel;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexBuildState;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.AddFieldReq;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.response.DescribeCollectionResp;
import io.milvus.v2.service.index.response.DescribeIndexResp;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Versioned Milvus collection contract. Incompatible changes require a new collection name. */
final class MilvusMemorySchema {

    static final int SCHEMA_VERSION = 2;
    static final String ID = "id";
    static final String TENANT_ID = "tenant_id";
    static final String APP_ID = "app_id";
    static final String USER_ID = "user_id";
    static final String TASK_ID = "task_id";
    static final String MEMORY_TYPE = "memory_type";
    static final String CONTENT = "content";
    static final String METADATA = "metadata";
    static final String EMBEDDING_MODEL = "embedding_model";
    static final String EMBEDDING_VERSION = "embedding_version";
    static final String CREATED_AT = "created_at";
    static final String ROW_SCHEMA_VERSION = "schema_version";
    static final String VECTOR = "embedding";
    static final String INDEX_NAME = "embedding_cosine_idx";
    static final IndexParam.IndexType INDEX_TYPE = IndexParam.IndexType.AUTOINDEX;
    static final IndexParam.MetricType METRIC_TYPE = IndexParam.MetricType.COSINE;
    static final List<String> OUTPUT_FIELDS = List.of(
            TENANT_ID, APP_ID, USER_ID, TASK_ID, MEMORY_TYPE, CONTENT, METADATA,
            EMBEDDING_MODEL, EMBEDDING_VERSION, CREATED_AT, ROW_SCHEMA_VERSION);

    private static final int ID_LENGTH = 64;
    private static final int TASK_ID_LENGTH = 128;
    private static final int MEMORY_TYPE_LENGTH = 32;
    private static final int CONTENT_LENGTH = 32_768;
    private static final int EMBEDDING_MODEL_LENGTH = 128;
    private static final int EMBEDDING_VERSION_LENGTH = 64;

    private MilvusMemorySchema() {
    }

    static CreateCollectionReq.CollectionSchema createSchema(int dimension) {
        CreateCollectionReq.CollectionSchema schema = CreateCollectionReq.CollectionSchema.builder()
                .enableDynamicField(false)
                .build();
        schema.addField(varchar(ID, ID_LENGTH, true));
        schema.addField(scalar(TENANT_ID, DataType.Int64));
        schema.addField(scalar(APP_ID, DataType.Int64));
        schema.addField(scalar(USER_ID, DataType.Int64));
        schema.addField(varchar(TASK_ID, TASK_ID_LENGTH, false));
        schema.addField(varchar(MEMORY_TYPE, MEMORY_TYPE_LENGTH, false));
        schema.addField(varchar(CONTENT, CONTENT_LENGTH, false));
        schema.addField(scalar(METADATA, DataType.JSON));
        schema.addField(varchar(EMBEDDING_MODEL, EMBEDDING_MODEL_LENGTH, false));
        schema.addField(varchar(EMBEDDING_VERSION, EMBEDDING_VERSION_LENGTH, false));
        schema.addField(scalar(CREATED_AT, DataType.Int64));
        schema.addField(scalar(ROW_SCHEMA_VERSION, DataType.Int32));
        schema.addField(AddFieldReq.builder()
                .fieldName(VECTOR)
                .dataType(DataType.FloatVector)
                .dimension(dimension)
                .build());
        return schema;
    }

    static IndexParam indexParam() {
        return IndexParam.builder()
                .fieldName(VECTOR)
                .indexName(INDEX_NAME)
                .indexType(INDEX_TYPE)
                .metricType(METRIC_TYPE)
                .build();
    }

    static void validateCollection(DescribeCollectionResp description, int dimension) {
        if (description == null || description.getCollectionSchema() == null) {
            throw incompatible("collection description or schema is absent");
        }
        if (Boolean.TRUE.equals(description.getEnableDynamicField())
                || description.getCollectionSchema().isEnableDynamicField()) {
            throw incompatible("dynamic fields must be disabled");
        }
        if (Boolean.TRUE.equals(description.getAutoID())) {
            throw incompatible("primary keys must be application assigned");
        }
        if (!ID.equals(description.getPrimaryFieldName())) {
            throw incompatible("primary field must be " + ID);
        }
        if (description.getConsistencyLevel() != ConsistencyLevel.STRONG) {
            throw incompatible("consistency level must be STRONG");
        }

        Map<String, FieldContract> expected = expectedFields(dimension);
        List<CreateCollectionReq.FieldSchema> actualFields =
                description.getCollectionSchema().getFieldSchemaList();
        if (actualFields == null || actualFields.size() != expected.size()) {
            throw incompatible("field set does not match schema v" + SCHEMA_VERSION);
        }
        for (CreateCollectionReq.FieldSchema actual : actualFields) {
            FieldContract contract = expected.remove(actual.getName());
            if (contract == null) {
                throw incompatible("unexpected field " + actual.getName());
            }
            contract.validate(actual);
        }
        if (!expected.isEmpty()) {
            throw incompatible("missing fields " + expected.keySet());
        }
    }

    static void validateIndex(DescribeIndexResp description) {
        DescribeIndexResp.IndexDesc index = description == null
                ? null : description.getIndexDescByFieldName(VECTOR);
        if (index == null) {
            throw incompatible("embedding index is absent");
        }
        if (!INDEX_NAME.equals(index.getIndexName())
                || index.getIndexType() != INDEX_TYPE
                || index.getMetricType() != METRIC_TYPE) {
            throw incompatible("embedding index contract does not match " + INDEX_NAME);
        }
        if (index.getIndexState() == IndexBuildState.Failed) {
            throw incompatible("embedding index build failed");
        }
        if (index.getIndexState() != IndexBuildState.Finished) {
            throw new IllegalStateException("Milvus memory index is not ready: " + index.getIndexState());
        }
    }

    private static AddFieldReq varchar(String name, int maxLength, boolean primary) {
        return AddFieldReq.builder()
                .fieldName(name)
                .dataType(DataType.VarChar)
                .maxLength(maxLength)
                .isPrimaryKey(primary)
                .autoID(false)
                .build();
    }

    private static AddFieldReq scalar(String name, DataType type) {
        return AddFieldReq.builder()
                .fieldName(name)
                .dataType(type)
                .build();
    }

    private static Map<String, FieldContract> expectedFields(int dimension) {
        Map<String, FieldContract> fields = new LinkedHashMap<>();
        fields.put(ID, new FieldContract(DataType.VarChar, ID_LENGTH, null, true, false));
        fields.put(TENANT_ID, new FieldContract(DataType.Int64, null, null, false, false));
        fields.put(APP_ID, new FieldContract(DataType.Int64, null, null, false, false));
        fields.put(USER_ID, new FieldContract(DataType.Int64, null, null, false, false));
        fields.put(TASK_ID, new FieldContract(DataType.VarChar, TASK_ID_LENGTH, null, false, false));
        fields.put(MEMORY_TYPE, new FieldContract(DataType.VarChar, MEMORY_TYPE_LENGTH, null, false, false));
        fields.put(CONTENT, new FieldContract(DataType.VarChar, CONTENT_LENGTH, null, false, false));
        fields.put(METADATA, new FieldContract(DataType.JSON, null, null, false, false));
        fields.put(EMBEDDING_MODEL,
                new FieldContract(DataType.VarChar, EMBEDDING_MODEL_LENGTH, null, false, false));
        fields.put(EMBEDDING_VERSION,
                new FieldContract(DataType.VarChar, EMBEDDING_VERSION_LENGTH, null, false, false));
        fields.put(CREATED_AT, new FieldContract(DataType.Int64, null, null, false, false));
        fields.put(ROW_SCHEMA_VERSION, new FieldContract(DataType.Int32, null, null, false, false));
        fields.put(VECTOR, new FieldContract(DataType.FloatVector, null, dimension, false, false));
        return fields;
    }

    private static IllegalStateException incompatible(String reason) {
        return new IllegalStateException(
                "Milvus memory collection is incompatible with schema v" + SCHEMA_VERSION + ": " + reason);
    }

    private record FieldContract(
            DataType dataType,
            Integer maxLength,
            Integer dimension,
            boolean primary,
            boolean nullable
    ) {
        private void validate(CreateCollectionReq.FieldSchema actual) {
            boolean actualPrimary = Boolean.TRUE.equals(actual.getIsPrimaryKey());
            boolean actualNullable = Boolean.TRUE.equals(actual.getIsNullable());
            if (actual.getDataType() != dataType
                    || maxLength != null && !maxLength.equals(actual.getMaxLength())
                    || dimension != null && !dimension.equals(actual.getDimension())
                    || actualPrimary != primary
                    || actualNullable != nullable
                    || primary && Boolean.TRUE.equals(actual.getAutoID())) {
                throw incompatible("field " + actual.getName() + " does not match its contract");
            }
        }
    }
}
