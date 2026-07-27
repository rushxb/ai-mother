package com.rush.rushaicodemother.service.devserver.persistence;

/** 持久开发服务器生命周期状态由编排和持久性共享。 */
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
