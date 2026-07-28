package com.rush.rushaicodemother.orchestration.runtime.identity;

import org.springframework.stereotype.Component;

import java.util.UUID;

/** 默认的基于UUID的生成任务身份策略。 */
@Component
public class UuidGenerationTaskIdGenerator implements GenerationTaskIdGenerator {

    /**
 * 返回{@code next}编号。
 *
 * @return 处理后的{@code Uuid}生成任务编号生成器文本
 */
    @Override
    public String nextId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
