package com.rush.rushaicodemother.orchestration.edit;

import com.rush.rushaicodemother.orchestration.patch.PatchOperation;
import com.rush.rushaicodemother.orchestration.patch.PatchWorkspaceException;

import java.util.List;
import java.util.Objects;

/**
 * 管理一次编辑尝试的工作区事务边界。
 *
 * <p>事务从首次补丁应用前开始，后续修复轮次可继续纳入新文件；只有验证及相关成功副作用
 * 全部完成后才允许提交。未显式提交的事务在关闭时自动恢复到事务开始前状态。</p>
 */
public final class EditWorkspaceTransaction implements AutoCloseable {

    private final String taskId;
    private final EditFileSnapshotService snapshotService;
    private final EditFileSnapshotService.EditFileSnapshot snapshot;
    private State state = State.OPEN;
    private EditFileSnapshotService.RestoreResult rollbackResult;

    EditWorkspaceTransaction(String taskId,
                             EditFileSnapshotService snapshotService,
                             EditFileSnapshotService.EditFileSnapshot snapshot) {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("编辑事务任务标识不能为空");
        }
        this.taskId = taskId;
        this.snapshotService = Objects.requireNonNull(snapshotService, "snapshotService");
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
    }

    /** 将后续修复轮次涉及但尚未捕获的文件纳入原始快照。 */
    public synchronized void include(List<PatchOperation> patchOperations) throws PatchWorkspaceException {
        requireOpen("扩展快照");
        snapshotService.captureMissing(snapshot, patchOperations);
    }

    /** 标记验证通过的编辑已提交；重复提交保持幂等。 */
    public synchronized void commit() {
        if (state == State.COMMITTED) {
            return;
        }
        requireOpen("提交");
        state = State.COMMITTED;
    }

    /** 恢复事务开始前状态；重复回滚返回首次恢复结果。 */
    public synchronized EditFileSnapshotService.RestoreResult rollback() {
        if (state == State.ROLLED_BACK || state == State.ROLLBACK_FAILED) {
            return rollbackResult;
        }
        requireOpen("回滚");
        rollbackResult = snapshotService.restore(taskId, snapshot);
        state = rollbackResult.failedFiles().isEmpty() ? State.ROLLED_BACK : State.ROLLBACK_FAILED;
        return rollbackResult;
    }

    /** 返回当前事务状态。 */
    public synchronized State state() {
        return state;
    }

    /** 未提交即退出作用域时自动回滚，避免部分编辑泄漏到工作区。 */
    @Override
    public synchronized void close() {
        if (state == State.OPEN) {
            rollback();
        }
    }

    private void requireOpen(String operation) {
        if (state != State.OPEN) {
            throw new IllegalStateException("编辑事务处于 " + state + " 状态，无法执行" + operation);
        }
    }

    public enum State {
        OPEN,
        COMMITTED,
        ROLLED_BACK,
        ROLLBACK_FAILED
    }
}