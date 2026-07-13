package com.rush.rushaicodemother.service.devserver;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

/** 已启动的 Dev Server 进程及其输出消费任务。 */
record DevServerProcessSession(
        Path projectDirectory,
        int port,
        Process process,
        CompletableFuture<Void> outputCompletion
) {

    DevServerProcessSession {
        if (projectDirectory == null || process == null || outputCompletion == null) {
            throw new IllegalArgumentException("Dev Server 进程会话参数不能为空");
        }
    }
}