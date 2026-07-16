package com.rush.rushaicodemother.orchestration.runtime.identity;

import org.springframework.stereotype.Component;

import java.util.UUID;

/** Default UUID-based generation task identity strategy. */
@Component
public class UuidGenerationTaskIdGenerator implements GenerationTaskIdGenerator {

    @Override
    public String nextId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
