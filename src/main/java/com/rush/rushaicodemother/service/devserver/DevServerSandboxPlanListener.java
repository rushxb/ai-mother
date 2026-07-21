package com.rush.rushaicodemother.service.devserver;

import com.rush.rushaicodemother.infrastructure.sandbox.SandboxProcessPlan;

/** Receives a durable sandbox resource manifest before any Dev Server resource is started. */
public interface DevServerSandboxPlanListener {

    void onPlanPrepared(Long appId, SandboxProcessPlan plan);

    static DevServerSandboxPlanListener noOp() {
        return (appId, plan) -> {
        };
    }
}
