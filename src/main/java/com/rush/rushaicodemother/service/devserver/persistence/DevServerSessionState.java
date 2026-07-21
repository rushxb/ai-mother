package com.rush.rushaicodemother.service.devserver.persistence;

/** Durable Dev Server lifecycle states shared by orchestration and persistence. */
public enum DevServerSessionState {
    STARTING,
    RUNNING,
    STOPPING,
    RECOVERING,
    STOPPED,
    FAILED;

    public boolean isActive() {
        return this == STARTING || this == RUNNING || this == STOPPING || this == RECOVERING;
    }
}
