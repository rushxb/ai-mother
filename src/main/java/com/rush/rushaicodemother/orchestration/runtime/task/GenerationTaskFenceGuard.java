package com.rush.rushaicodemother.orchestration.runtime.task;

import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContextService;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionFence;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionPolicyException;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.DurableGenerationTaskRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;

/** 在工作空间产生副作用之前立即进行故障关闭耐用栅栏检查。 */
@Service
public class GenerationTaskFenceGuard {

    private final DurableGenerationTaskRepository repository;
    private final GenerationExecutionContextService executionContextService;
    private final Clock clock;

    @Autowired
    public GenerationTaskFenceGuard(DurableGenerationTaskRepository repository,
                                    GenerationExecutionContextService executionContextService) {
        this(repository, executionContextService, Clock.systemUTC());
    }

    GenerationTaskFenceGuard(DurableGenerationTaskRepository repository,
                             GenerationExecutionContextService executionContextService,
                             Clock clock) {
        this.repository = repository;
        this.executionContextService = executionContextService;
        this.clock = clock;
    }

    /**
 * 断言任务围栏仍指向当前有效执行轮次。
 *
 * @param taskId 任务编号
 */
    public void assertCurrent(String taskId) {
        GenerationExecutionFence fence = executionContextService.getExecutionFence(taskId).orElse(null);
        if (fence == null) {
            if (repository.findByTaskId(taskId).isEmpty()) {
                return;
            }
            executionContextService.cancelByTaskId(taskId, "worker_fence_missing");
            throw new GenerationExecutionPolicyException(
                    "durable generation task has no local execution fence");
        }
        assertCurrent(fence);
    }

    /** 检查提供的不可变栅栏，从不重新读取可变任务级上下文。 */
    public void assertCurrent(GenerationExecutionFence fence) {
        if (fence == null) {
            throw new GenerationExecutionPolicyException("generation task execution fence is missing");
        }
        if (repository.isCurrentFence(fence, clock.instant())) {
            return;
        }
        executionContextService.cancelByTaskId(fence.taskId(), "worker_fence_rejected");
        throw new GenerationExecutionPolicyException(
                "generation task execution fence is no longer current");
    }
}
