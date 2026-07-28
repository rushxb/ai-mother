package com.rush.rushaicodemother.orchestration.artifact;

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

    /**
 * 根据给定参数创建当前对象。
 *
 * @param key 键
 * @param role 角色
 * @param title {@code title} 对应的调用参数
 * @param payload 载荷
 * @return 生成制品
 */
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
