package com.rush.rushaicodemother.orchestration.governance.audit;

/** 受控操作的有界审计结果。 */
public enum GenerationControlAuditOutcome {
    STARTED,
    SUCCESS,
    DENIED,
    REJECTED,
    FAILED;

    public boolean isTerminal() {
        return this != STARTED;
    }
}
