package com.rush.rushaicodemother.infrastructure.persistence.benchmark;

import com.rush.rushaicodemother.mapper.GenerationBenchmarkUsageMapper;
import com.rush.rushaicodemother.model.entity.GenerationTask;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkUsage;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkUsageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * MyBatis生成基准测试用量持久化仓储。
 */
@Repository
@RequiredArgsConstructor
public class MyBatisGenerationBenchmarkUsageRepository implements GenerationBenchmarkUsageRepository {

    private final GenerationBenchmarkUsageMapper mapper;

    @Override
    public GenerationBenchmarkUsage findByTaskId(String taskId) {
        if (taskId == null || !taskId.matches("[A-Za-z0-9_-]{1,128}")) {
            return GenerationBenchmarkUsage.empty();
        }
        GenerationTask task = mapper.selectUsageByTaskId(taskId);
        if (task == null) {
            return GenerationBenchmarkUsage.empty();
        }
        return new GenerationBenchmarkUsage(
                task.getTotalTokens() == null ? 0 : task.getTotalTokens(),
                task.getCreditCost() == null ? 0 : task.getCreditCost()
        );
    }
}
