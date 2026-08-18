package com.rush.rushaicodemother.infrastructure.sandbox;

/** 授予一个独立的生成代码进程的网络特权。 */
public enum SandboxNetworkPolicy {
    /** 构建与离线验证使用，不接入任何容器网络。 */
    NONE,
    /** 仅供受管依赖安装访问可信依赖源。 */
    DEPENDENCY_EGRESS,
    /** 仅供带显式端口的运行时接入内部预览网络。 */
    RUNTIME_INTERNAL
}
