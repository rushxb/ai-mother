package com.rush.rushaicodemother.infrastructure.process;

/** 外部进程生命周期回调，用于在不泄漏执行细节的前提下注册和注销进程。 */
public interface ManagedProcessLifecycle {

    ManagedProcessLifecycle NO_OP = new ManagedProcessLifecycle() {
    };

    default void onStarted(Process process) {
    }

    default void onFinished(Process process) {
    }
}
