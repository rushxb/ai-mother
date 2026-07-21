package com.rush.rushaicodemother.orchestration.dag;

/** Typed lifecycle for the deterministic generation-agent workflow. */
public enum AgentRuntimeState {
    INITIALIZED(false),
    RUNNING(false),
    VERIFYING(false),
    REPAIRING(false),
    COMPLETED(true),
    FAILED(true);

    private final boolean terminal;

    AgentRuntimeState(boolean terminal) {
        this.terminal = terminal;
    }

    public boolean terminal() {
        return terminal;
    }
}
