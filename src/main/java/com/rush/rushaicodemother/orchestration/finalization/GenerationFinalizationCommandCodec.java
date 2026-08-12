package com.rush.rushaicodemother.orchestration.finalization;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;

/** 发布恢复所需终态命令的稳定 JSON 编解码器。 */
public final class GenerationFinalizationCommandCodec {

    public static final int CURRENT_SCHEMA_VERSION = 1;

    private static final JsonMapper MAPPER = JsonMapper.builder()
            .findAndAddModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();

    private GenerationFinalizationCommandCodec() {
    }

    public static String toJson(GenerationFinalizationCommand command) {
        try {
            return MAPPER.writeValueAsString(command);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("生成终态意图序列化失败", exception);
        }
    }

    public static GenerationFinalizationCommand fromJson(String json) {
        try {
            return MAPPER.readValue(json, GenerationFinalizationCommand.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("生成终态意图反序列化失败", exception);
        }
    }
}
