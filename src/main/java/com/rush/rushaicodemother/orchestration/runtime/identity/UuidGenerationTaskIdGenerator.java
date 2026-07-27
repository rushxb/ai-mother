package com.rush.rushaicodemother.orchestration.runtime.identity;

import org.springframework.stereotype.Component;

import java.util.UUID;

/** 默认的基于UUID的生成任务身份策略。 */
@Component
public class UuidGenerationTaskIdGenerator implements GenerationTaskIdGenerator {

    @Override
    public String nextId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
