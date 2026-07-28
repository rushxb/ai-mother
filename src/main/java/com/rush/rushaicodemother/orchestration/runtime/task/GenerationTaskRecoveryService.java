package com.rush.rushaicodemother.orchestration.runtime.task;

import com.rush.rushaicodemother.infrastructure.diagnostic.LogExceptionSanitizer;
import com.rush.rushaicodemother.orchestration.GenerationAppStateService;
import com.rush.rushaicodemother.orchestration.dag.GenerationDagCheckpointRecoveryPolicy;
import com.rush.rushaicodemother.orchestration.dag.GenerationOrchestrationTaskStore;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContextService;
import com.rush.rushaicodemother.orchestration.tool.GenerationToolContinuationScheduler;
import com.rush.rushaicodemother.orchestration.tool.ToolApprovalRecord;
import com.rush.rushaicodemother.orchestration.tool.ToolExecutionRecoveryService;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.DurableGenerationTaskRepository;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.GenerationTaskRecoveryCandidate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * 恢复位于确定性版本化检查点边界的过期任务，并终止其余过期任务。
 * 取消和截止日期始终优先，执行轮次围栏用于阻止旧工作器快照被新租约复用。
 */
@Slf4j
@Service
public class GenerationTaskRecoveryService {

    private final DurableGenerationTaskRepository repository;
    private final GenerationTaskLeaseProperties properties;
    private final GenerationTaskRecoveryPolicy recoveryPolicy;
    private final GenerationAppStateService generationAppStateService;
    private final GenerationExecutionContextService executionContextService;
    private final ToolExecutionRecoveryService toolExecutionRecoveryService;
    private final GenerationToolContinuationScheduler toolContinuationScheduler;
    private final GenerationOrchestrationTaskStore orchestrationTaskStore;
    private final GenerationTaskDispatcher taskDispatcher;
    private final Clock clock;

    @Autowired
    public GenerationTaskRecoveryService(DurableGenerationTaskRepository repository,
                                         GenerationTaskLeaseProperties properties,
                                         GenerationTaskRecoveryPolicy recoveryPolicy,
                                         GenerationAppStateService generationAppStateService,
                                         GenerationExecutionContextService executionContextService,
                                         ToolExecutionRecoveryService toolExecutionRecoveryService,
                                         GenerationToolContinuationScheduler toolContinuationScheduler,
                                         GenerationOrchestrationTaskStore orchestrationTaskStore,
                                         GenerationTaskDispatcher taskDispatcher) {
        this(repository, properties, recoveryPolicy, generationAppStateService,
                executionContextService, toolExecutionRecoveryService,
                toolContinuationScheduler, orchestrationTaskStore, taskDispatcher, Clock.systemUTC());
    }

    /** 创建生成任务恢复服务实例并完成必要的依赖和初始状态设置。 */
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
        this.toolExecutionRecoveryService = null;
        this.toolContinuationScheduler = null;
        this.orchestrationTaskStore = null;
        this.taskDispatcher = null;
        this.clock = clock;
    }

    /** 创建生成任务恢复服务实例并完成必要的依赖和初始状态设置。 */
    GenerationTaskRecoveryService(DurableGenerationTaskRepository repository,
                                  GenerationTaskLeaseProperties properties,
                                  GenerationTaskRecoveryPolicy recoveryPolicy,
                                  GenerationAppStateService generationAppStateService,
                                  GenerationExecutionContextService executionContextService,
                                  ToolExecutionRecoveryService toolExecutionRecoveryService,
                                  GenerationToolContinuationScheduler toolContinuationScheduler,
                                  GenerationOrchestrationTaskStore orchestrationTaskStore,
                                  GenerationTaskDispatcher taskDispatcher,
                                  Clock clock) {
        this.repository = repository;
        this.properties = properties;
        this.recoveryPolicy = recoveryPolicy;
        this.generationAppStateService = generationAppStateService;
        this.executionContextService = executionContextService;
        this.toolExecutionRecoveryService = toolExecutionRecoveryService;
        this.toolContinuationScheduler = toolContinuationScheduler;
        this.orchestrationTaskStore = orchestrationTaskStore;
        this.taskDispatcher = taskDispatcher;
        this.clock = clock;
    }

    /**
 * 恢复{@code Expired}任务。
 *
 * @return 计算或处理后的数值结果
 */
    public int recoverExpiredTasks() {
        Instant now = clock.instant();
        List<GenerationTaskRecoveryCandidate> candidates = repository.findExpiredLeases(
                now, properties.getRecoveryBatchSize());
        int recovered = 0;
        // 按既定顺序逐项处理，并在达到资源或状态边界时提前结束。
        for (GenerationTaskRecoveryCandidate candidate : candidates) {
            try {
                GenerationTaskRecoveryDecision decision = recoveryPolicy.decide(candidate, now);
                if (isRecoverableToolOrphan(decision)) {
                    ToolApprovalRecord approval = toolExecutionRecoveryService
                            .recover(candidate, now)
                            .orElse(null);
                    if (approval != null) {
                        recovered++;
                        try {
                            toolContinuationScheduler.schedule(approval);
                        } catch (RuntimeException dispatchFailure) {
                            log.warn("已恢复的工具续执行仍在队列中等待重试，taskId: {}",
                                    candidate.taskId(), LogExceptionSanitizer.sanitize(dispatchFailure));
                        }
                        continue;
                    }
                }
                if (isRecoverableDagOrphan(candidate, decision)
                        && hasRecoverableDagCheckpoint(candidate)
                        && repository.requeueExpiredLease(candidate, now, "checkpoint_resume")) {
                    recovered++;
                    try {
                        taskDispatcher.dispatch(candidate.taskId());
                    } catch (RuntimeException dispatchFailure) {
                        log.warn("已恢复的生成任务仍在队列中等待重新分派，taskId: {}",
                                candidate.taskId(), LogExceptionSanitizer.sanitize(dispatchFailure));
                    }
                    continue;
                }
                if (!repository.finalizeExpiredLease(
                        candidate, decision.status(), now, decision.reason())) {
                    continue;
                }
                recovered++;
                executionContextService.cancelByTaskId(candidate.taskId(), decision.reason());
                generationAppStateService.releaseOwnedGenerationState(
                        candidate.appId(), candidate.taskId(), candidate.executionEpoch());
                log.warn(
                        "过期生成任务已进入终态，taskId: {}，status: {}，previousOwner: {}",
                        candidate.taskId(), decision.status().getValue(), candidate.leaseOwner()
                );
            } catch (RuntimeException recoveryFailure) {
                log.error("处理过期生成任务失败，taskId: {}",
                        candidate.taskId(), LogExceptionSanitizer.sanitize(recoveryFailure));
            }
        }
        return recovered;
    }

    private boolean isRecoverableToolOrphan(GenerationTaskRecoveryDecision decision) {
        return toolExecutionRecoveryService != null
                && toolContinuationScheduler != null
                && decision.status() == com.rush.rushaicodemother.model.enums.GenerationTaskStatus.FAILED
                && GenerationTaskRecoveryPolicy.ORPHAN_FAILURE_REASON.equals(decision.reason());
    }

    private boolean isRecoverableDagOrphan(GenerationTaskRecoveryCandidate candidate,
                                           GenerationTaskRecoveryDecision decision) {
        return orchestrationTaskStore != null
                && taskDispatcher != null
                && candidate.status() == com.rush.rushaicodemother.model.enums.GenerationTaskStatus.RUNNING
                && decision.status() == com.rush.rushaicodemother.model.enums.GenerationTaskStatus.FAILED
                && GenerationTaskRecoveryPolicy.ORPHAN_FAILURE_REASON.equals(decision.reason());
    }

    /** 判断是否存在{@code Recoverable}{@code Dag}检查点。 */
    private boolean hasRecoverableDagCheckpoint(GenerationTaskRecoveryCandidate candidate) {
        try {
            var checkpoint = orchestrationTaskStore.load(candidate.appId(), candidate.taskId()).orElse(null);
            if (checkpoint == null) {
                return false;
            }
            if (checkpoint.getExecutionEpoch() != candidate.executionEpoch()) {
                log.warn("生成 DAG 检查点执行轮次不匹配，taskId: {}，checkpointEpoch: {}，leaseEpoch: {}",
                        candidate.taskId(), checkpoint.getExecutionEpoch(), candidate.executionEpoch());
                return false;
            }
            GenerationDagCheckpointRecoveryPolicy.Assessment assessment =
                    GenerationDagCheckpointRecoveryPolicy.assess(checkpoint);
            if (!assessment.automaticallyRecoverable()) {
                log.warn("生成 DAG 检查点不能自动恢复，taskId: {}，原因: {}",
                        candidate.taskId(), assessment.reason());
            }
            return assessment.automaticallyRecoverable();
        } catch (RuntimeException corruptedCheckpoint) {
            log.warn("生成 DAG 检查点不可用于恢复，taskId: {}",
                    candidate.taskId(), LogExceptionSanitizer.sanitize(corruptedCheckpoint));
            return false;
        }
    }
}
