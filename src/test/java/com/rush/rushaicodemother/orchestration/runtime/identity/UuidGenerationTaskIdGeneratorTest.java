package com.rush.rushaicodemother.orchestration.runtime.identity;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UuidGenerationTaskIdGeneratorTest {

    @Test
    void generatedIdsMustBeSafeAndUnique() {
        UuidGenerationTaskIdGenerator generator = new UuidGenerationTaskIdGenerator();
        Set<String> taskIds = new HashSet<>();

        for (int index = 0; index < 1_000; index++) {
            String taskId = generator.nextId();
            assertEquals(32, taskId.length());
            assertTrue(taskId.matches("[A-Za-z0-9_-]{1,128}"));
            assertTrue(taskIds.add(taskId), "task id must be unique");
        }
    }
}
