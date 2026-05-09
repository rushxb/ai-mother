package com.yupi.yuaicodemother.orchestration.artifact;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 多智能体编排产物。
 *
 * @param key       产物键，供后续节点按键读取
 * @param role      产出该产物的智能体角色
 * @param title     面向人类可读的产物标题
 * @param payload   结构化产物内容
 * @param createdAt 产出时间
 */
public record GenerationArtifact(
        String key,
        String role,
        String title,
        Map<String, Object> payload,
        LocalDateTime createdAt
) {

    public static GenerationArtifact of(String key, String role, String title, Map<String, Object> payload) {
        return new GenerationArtifact(
                key,
                role,
                title,
                payload == null ? Map.of() : new LinkedHashMap<>(payload),
                LocalDateTime.now()
        );
    }
}
