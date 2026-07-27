package com.rush.rushaicodemother.service.devserver;

import com.rush.rushaicodemother.infrastructure.sandbox.SandboxProcessPlan;

/** 在启动任何开发服务器资源之前接收持久沙箱资源清单。 */
public interface DevServerSandboxPlanListener {

    void onPlanPrepared(Long appId, SandboxProcessPlan plan);

    static DevServerSandboxPlanListener noOp() {
        return (appId, plan) -> {
        };
    }
}
