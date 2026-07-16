package com.rush.rushaicodemother.orchestration.runtime.task;

import com.rush.rushaicodemother.model.enums.GenerationTaskStatus;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.DurableGenerationTaskRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;

/** Application seam for durable submission, activation, cancellation and terminal cleanup. */
@Service
public class GenerationTaskRuntimeLifecycleService {

    private final DurableGenerationTaskRepository repository;
    private final GenerationTaskLeaseCoordinator leaseCoordinator;
    private final Clock clock;

    @Autowired
    public GenerationTaskRuntimeLifecycleService(DurableGenerationTaskRepository repository,
                                                 GenerationTaskLeaseCoordinator leaseCoordinator) {
        this(repository, leaseCoordinator, Clock.systemUTC());
    }

    GenerationTaskRuntimeLifecycleService(DurableGenerationTaskRepository repository,
                                          GenerationTaskLeaseCoordinator leaseCoordinator,
                                          Clock clock) {
        this.repository = repository;
        this.leaseCoordinator = leaseCoordinator;
        this.clock = clock;
    }

    public void submit(GenerationTaskExecution execution, String route) {
        repository.createSubmitted(leaseCoordinator.submissionRecord(execution, route));
        leaseCoordinator.trackSubmitted(execution.taskId());
    }

    public void activate(String taskId) {
        leaseCoordinator.activate(taskId);
    }

    public boolean requestCancellation(String taskId, String reason) {
        return repository.requestCancellation(taskId, reason, clock.instant());
    }

    public void complete(String taskId, GenerationTaskStatus status, String reason) {
        try {
            repository.complete(taskId, status, reason, leaseCoordinator.ownerId(), clock.instant());
        } finally {
            leaseCoordinator.release(taskId);
        }
    }
}
