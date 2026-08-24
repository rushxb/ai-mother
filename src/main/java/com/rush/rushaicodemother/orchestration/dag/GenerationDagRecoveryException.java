package com.rush.rushaicodemother.orchestration.dag;

/** 失败关闭拒绝无法无歧义重播的检查点。 */
public final class GenerationDagRecoveryException extends IllegalStateException {

    private final Reason reason;

    /**
 * 创建生成{@code Dag}恢复异常实例并完成必要的依赖和初始状态设置。
 *
 * @param reason 原因
 * @param message 消息内容
 */
    public GenerationDagRecoveryException(Reason reason, String message) {
        super(message);
        if (reason == null) {
            throw new IllegalArgumentException("DAG recovery failure reason is required");
        }
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    public enum Reason {
        AMBIGUOUS_NODE,
        ARTIFACT_MISMATCH,
        GRAPH_MISMATCH,
        LEGACY_CHECKPOINT,
        INVALID_GRAPH
    }
}
