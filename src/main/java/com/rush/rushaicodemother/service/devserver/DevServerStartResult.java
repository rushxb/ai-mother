package com.rush.rushaicodemother.service.devserver;

/**
 * Dev Server 启动结果，用于区分当前调用者新建的会话与已存在会话。
 */
public record DevServerStartResult(int port, boolean startedByCaller) {

    /** 创建开发服务器开始结果实例并完成必要的依赖和初始状态设置。 */
    public DevServerStartResult {
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Dev Server 端口无效");
        }
    }

    static DevServerStartResult started(int port) {
        return new DevServerStartResult(port, true);
    }

    static DevServerStartResult reused(int port) {
        return new DevServerStartResult(port, false);
    }
}
