package com.rush.rushaicodemother.architecture;

import com.mybatisflex.core.BaseMapper;
import com.rush.rushaicodemother.mapper.GenerationBenchmarkUsageMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerationBenchmarkPersistenceBoundaryArchitectureTest {

    @Test
    void benchmarkDomainMustUsePersistencePorts() throws Exception {
        Path root = Path.of("src", "main", "java", "com", "rush", "rushaicodemother",
                "orchestration", "benchmark");
        try (var files = Files.walk(root)) {
            String source = files.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .map(this::read)
                    .reduce("", (left, right) -> left + "\n" + right);
            assertFalse(source.contains("com.rush.rushaicodemother.mapper"));
            assertFalse(source.contains("infrastructure.persistence"));
            assertTrue(source.contains("GenerationBenchmarkUsageRepository"));
        }
    }

    @Test
    void usageMapperMustRemainExplicitAndBounded() throws Exception {
        assertFalse(BaseMapper.class.isAssignableFrom(GenerationBenchmarkUsageMapper.class));
        String source = Files.readString(Path.of(
                "src", "main", "java", "com", "rush", "rushaicodemother",
                "mapper", "GenerationBenchmarkUsageMapper.java"));
        assertTrue(source.contains("WHERE taskId = #{taskId}"));
        assertTrue(source.contains("LIMIT 1"));
        assertTrue(source.contains("isDelete = 0"));
    }

    private String read(Path path) {
        try {
            return Files.readString(path);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
