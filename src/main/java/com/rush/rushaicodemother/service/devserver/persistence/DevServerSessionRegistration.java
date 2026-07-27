package com.rush.rushaicodemother.service.devserver.persistence;

import java.nio.file.Path;

/** 以原子方式保留持久开发服务器会话所需的数据。 */
public record DevServerSessionRegistration(
        Long appId,
        Long userId,
        String nodeId,
        String leaseOwner,
        Path projectDirectory,
        int port
) {
}
