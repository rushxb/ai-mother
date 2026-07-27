package com.rush.rushaicodemother.orchestration.dag;

/** 确定性生成代理工作流程的类型化生命周期。 */
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
