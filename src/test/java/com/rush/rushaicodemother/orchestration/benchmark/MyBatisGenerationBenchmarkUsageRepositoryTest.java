package com.rush.rushaicodemother.orchestration.benchmark;

import com.rush.rushaicodemother.infrastructure.persistence.benchmark.MyBatisGenerationBenchmarkUsageRepository;
import com.rush.rushaicodemother.mapper.GenerationBenchmarkUsageMapper;
import com.rush.rushaicodemother.model.entity.GenerationTask;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MyBatisGenerationBenchmarkUsageRepositoryTest {

    @Test
    void shouldReadBoundedUsageProjection() {
        GenerationBenchmarkUsageMapper mapper = mock(GenerationBenchmarkUsageMapper.class);
        when(mapper.selectUsageByTaskId("task-1")).thenReturn(GenerationTask.builder()
                .totalTokens(1234L)
                .creditCost(8L)
                .build());
        MyBatisGenerationBenchmarkUsageRepository repository =
                new MyBatisGenerationBenchmarkUsageRepository(mapper);

        GenerationBenchmarkUsage usage = repository.findByTaskId("task-1");

        assertEquals(1234L, usage.totalTokens());
        assertEquals(8L, usage.creditCost());
    }
}
