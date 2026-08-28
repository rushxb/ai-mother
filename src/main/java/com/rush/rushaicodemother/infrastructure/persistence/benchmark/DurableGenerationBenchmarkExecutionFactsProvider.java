package com.rush.rushaicodemother.infrastructure.persistence.benchmark;

import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkExecutionFacts;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkExecutionFactsProvider;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.DurableGenerationTaskRepository;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Optional;

/** 从持久任务命令读取 Benchmark 执行事实的生产适配器。 */
@Component
public class DurableGenerationBenchmarkExecutionFactsProvider
        implements GenerationBenchmarkExecutionFactsProvider {

    private final DurableGenerationTaskRepository taskRepository;

    public DurableGenerationBenchmarkExecutionFactsProvider(
            DurableGenerationTaskRepository taskRepository
    ) {
        this.taskRepository = Objects.requireNonNull(
                taskRepository, "持久任务仓储不能为空");
    }

    @Override
    public Optional<GenerationBenchmarkExecutionFacts> findByTaskId(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return Optional.empty();
        }
        return taskRepository.findCommandByTaskId(taskId)
                .map(command -> new GenerationBenchmarkExecutionFacts(
                        command.taskId(),
                        command.appId(),
                        command.codeGenType(),
                        command.fallbackReason()
                ));
    }
}
