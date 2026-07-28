package com.rush.rushaicodemother.orchestration.runtime.task.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;

/** 用于在工作实例之间持久保存任务命令的 JSON 编解码器。 */
public final class GenerationTaskCommandCodec {

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
        try {
            return MAPPER.readValue(json, GenerationTaskCommand.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Unable to deserialize generation task command", exception);
        }
    }
}
