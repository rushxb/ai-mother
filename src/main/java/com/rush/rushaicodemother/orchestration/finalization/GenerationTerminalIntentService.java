package com.rush.rushaicodemother.orchestration.finalization;

import com.rush.rushaicodemother.orchestration.runtime.task.persistence.DurableGenerationTaskRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.Objects;

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

    /**
     * 仅在发布文件系统已完整回滚后，撤销尚未最终化的成功意图。
     *
     * @return 当前执行围栏仍有效且精确命令已撤销时返回 {@code true}
     */
    public boolean abortPrepared(GenerationFinalizationCommand command) {
        if (command == null || command.executionFence() == null) {
            throw new IllegalArgumentException("撤销终态意图必须包含执行围栏");
        }
        return repository.abortFinalizationIntent(command, clock.instant());
    }

    /**
     * 读取发布前已经冻结的终态命令；缺失或串执行轮次时必须失败关闭。
     *
     * <p>文件系统发布与数据库终态无法组成同一事务。工作区一旦发布，后续正常收口和宕机恢复
     * 都必须重放同一份持久命令，不能临时拼装一份“看起来相同”的成功终态，否则会丢失发布前
     * 冻结的记忆、质量证据或执行所有权。</p>
     */
    public GenerationFinalizationCommand requirePrepared(GenerationFinalizationCommand expected) {
        if (expected == null || expected.executionFence() == null) {
            throw new IllegalArgumentException("已发布任务必须提供终态意图执行围栏");
        }
        GenerationFinalizationCommand prepared = repository.findFinalizationIntent(
                        expected.taskId(), expected.executionFence().executionEpoch())
                .orElseThrow(() -> new IllegalStateException("已发布任务缺少可恢复终态意图"));
        if (!Objects.equals(prepared.taskId(), expected.taskId())
                || !Objects.equals(prepared.appId(), expected.appId())
                || !Objects.equals(prepared.executionFence(), expected.executionFence())
                || prepared.status() != expected.status()) {
            throw new IllegalStateException("已发布任务终态意图与当前执行上下文不一致");
        }
        return prepared;
    }
}
