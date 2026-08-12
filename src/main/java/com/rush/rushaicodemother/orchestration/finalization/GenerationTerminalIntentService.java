package com.rush.rushaicodemother.orchestration.finalization;

import com.rush.rushaicodemother.orchestration.runtime.task.persistence.DurableGenerationTaskRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;

/** 在用户可见发布前冻结可恢复的完整终态命令。 */
@Service
public class GenerationTerminalIntentService {

    private final DurableGenerationTaskRepository repository;
    private final Clock clock;

    @Autowired
    public GenerationTerminalIntentService(DurableGenerationTaskRepository repository) {
        this(repository, Clock.systemUTC());
    }

    GenerationTerminalIntentService(DurableGenerationTaskRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public void prepare(GenerationFinalizationCommand command) {
        if (command == null || command.executionFence() == null) {
            throw new IllegalArgumentException("发布终态意图必须包含执行围栏");
        }
        repository.prepareFinalizationIntent(command, clock.instant());
    }

    public GenerationFinalizationCommand preparedOr(GenerationFinalizationCommand fallback) {
        if (fallback == null || fallback.executionFence() == null) {
            return fallback;
        }
        return repository.findFinalizationIntent(
                        fallback.taskId(), fallback.executionFence().executionEpoch())
                .orElse(fallback);
    }
}
