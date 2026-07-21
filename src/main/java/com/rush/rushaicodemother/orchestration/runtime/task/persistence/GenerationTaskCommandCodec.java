package com.rush.rushaicodemother.orchestration.runtime.task.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;

/** JSON codec for durable task commands persisted across worker instances. */
public final class GenerationTaskCommandCodec {

    private static final JsonMapper MAPPER = JsonMapper.builder()
            .findAndAddModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();

    private GenerationTaskCommandCodec() {
    }

    public static String toJson(GenerationTaskCommand command) {
        try {
            return MAPPER.writeValueAsString(command);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Unable to serialize generation task command", exception);
        }
    }

    public static GenerationTaskCommand fromJson(String json) {
        try {
            return MAPPER.readValue(json, GenerationTaskCommand.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Unable to deserialize generation task command", exception);
        }
    }
}
