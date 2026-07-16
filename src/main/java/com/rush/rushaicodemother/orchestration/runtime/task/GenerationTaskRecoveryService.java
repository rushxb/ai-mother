package com.rush.rushaicodemother.orchestration.runtime.task;

import com.rush.rushaicodemother.infrastructure.diagnostic.LogExceptionSanitizer;
import com.rush.rushaicodemother.orchestration.GenerationAppStateService;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContextService;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.DurableGenerationTaskRepository;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.GenerationTaskRecoveryCandidate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Terminalizes expired tasks that cannot yet be resumed because no versioned checkpoint exists.
 * This is deliberately honest recovery: it preserves cancellation/deadline semantics, prevents
 * zombie ownership, and never pretends that interrupted generation work has resumed.
 */
@Slf4j
@Service
public class GenerationTaskRecoveryService {

    private final DurableGenerationTaskRepository repository;
    private final GenerationTaskLeaseProperties properties;
    private final GenerationTaskRecoveryPolicy recoveryPolicy;
    private final GenerationAppStateService generationAppStateService;
    private final GenerationExecutionContextService executionContextService;
    private final Clock clock;

    @Autowired
    public GenerationTaskRecoveryService(DurableGenerationTaskRepository repository,
                                         GenerationTaskLeaseProperties properties,
                                         GenerationTaskRecoveryPolicy recoveryPolicy,
                                         GenerationAppStateService generationAppStateService,
                                         GenerationExecutionContextService executionContextService) {
        this(repository, properties, recoveryPolicy, generationAppStateService,
                executionContextService, Clock.systemUTC());
    }

    GenerationTaskRecoveryService(DurableGenerationTaskRepository repository,
                                  GenerationTaskLeaseProperties properties,
                                  GenerationTaskRecoveryPolicy recoveryPolicy,
                                  GenerationAppStateService generationAppStateService,
                                  GenerationExecutionContextService executionContextService,
                                  Clock clock) {
        this.repository = repository;
        this.properties = properties;
        this.recoveryPolicy = recoveryPolicy;
        this.generationAppStateService = generationAppStateService;
        this.executionContextService = executionContextService;
        this.clock = clock;
    }

    public int recoverExpiredTasks() {
        Instant now = clock.instant();
        List<GenerationTaskRecoveryCandidate> candidates = repository.findExpiredLeases(
                now, properties.getRecoveryBatchSize());
        int recovered = 0;
        for (GenerationTaskRecoveryCandidate candidate : candidates) {
            try {
                GenerationTaskRecoveryDecision decision = recoveryPolicy.decide(candidate, now);
                if (!repository.finalizeExpiredLease(
                        candidate, decision.status(), now, decision.reason())) {
                    continue;
                }
                recovered++;
                executionContextService.cancelByTaskId(candidate.taskId(), decision.reason());
                generationAppStateService.releaseOwnedGenerationState(candidate.appId(), candidate.taskId());
                log.warn(
                        "Expired generation task terminalized, taskId: {}, status: {}, previousOwner: {}",
                        candidate.taskId(), decision.status().getValue(), candidate.leaseOwner()
                );
            } catch (RuntimeException recoveryFailure) {
                log.error("Failed to terminalize expired generation task, taskId: {}",
                        candidate.taskId(), LogExceptionSanitizer.sanitize(recoveryFailure));
            }
        }
        return recovered;
    }
}
