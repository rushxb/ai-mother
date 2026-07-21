package com.rush.rushaicodemother.orchestration.dag;

/** Fail-closed rejection of a checkpoint that cannot be replayed without ambiguity. */
public final class GenerationDagRecoveryException extends IllegalStateException {

    private final Reason reason;

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
        GRAPH_MISMATCH,
        LEGACY_CHECKPOINT,
        INVALID_GRAPH
    }
}
