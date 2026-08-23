package com.rush.rushaicodemother.orchestration.runtime.task.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;

/** 用于在工作实例之间持久保存任务命令的 JSON 编解码器。 */
public final class GenerationTaskCommandCodec {

    private static final int FROZEN_SCENARIO_DECISION_SCHEMA_VERSION = 9;
    private static final int PREFLIGHT_USAGE_SCHEMA_VERSION = 10;

    private static final JsonMapper MAPPER = JsonMapper.builder()
            .findAndAddModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();

    private GenerationTaskCommandCodec() {
    }

    /**
 * 将当前对象转换为{@code Json}。
 *
 * @param command 命令
 * @return 处理后的{@code Json}文本
 */
    public static String toJson(GenerationTaskCommand command) {
        try {
            return MAPPER.writeValueAsString(command);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Unable to serialize generation task command", exception);
        }
    }

    /**
 * 根据输入数据创建当前对象。
 *
 * @param json {@code json} 对应的调用参数
 * @return 生成任务命令{@code Codec}
 */
    public static GenerationTaskCommand fromJson(String json) {
        JsonNode payload;
        try {
            payload = MAPPER.readTree(json);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Unable to deserialize generation task command", exception);
        }
        int schemaVersion = requireSchemaVersion(payload);
        requirePersistedFieldSince(
                payload, schemaVersion, "scenarioDecision", FROZEN_SCENARIO_DECISION_SCHEMA_VERSION);
        requirePersistedFieldSince(
                payload, schemaVersion, "preflightUsage", PREFLIGHT_USAGE_SCHEMA_VERSION);
        try {
            return MAPPER.treeToValue(payload, GenerationTaskCommand.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Unable to deserialize generation task command", exception);
        }
    }

    /**
     * 当前 schema 新增的冻结事实必须真实存在，不能被记录构造器的历史兼容默认值掩盖。
     */
    private static int requireSchemaVersion(JsonNode payload) {
        if (payload == null || !payload.isObject()) {
            throw new IllegalArgumentException("任务命令 JSON 必须是对象");
        }
        JsonNode schemaVersion = payload.get("schemaVersion");
        if (schemaVersion == null || !schemaVersion.isIntegralNumber()) {
            throw new IllegalArgumentException("任务命令 schemaVersion 必须是整数");
        }
        return schemaVersion.intValue();
    }

    private static void requirePersistedFieldSince(JsonNode payload,
                                                   int schemaVersion,
                                                   String fieldName,
                                                   int introducedSchemaVersion) {
        if (schemaVersion >= introducedSchemaVersion
                && (payload.get(fieldName) == null || payload.get(fieldName).isNull())) {
            throw new IllegalArgumentException(
                    "任务命令 schema " + schemaVersion + " 缺少必需字段: " + fieldName);
        }
    }
}
