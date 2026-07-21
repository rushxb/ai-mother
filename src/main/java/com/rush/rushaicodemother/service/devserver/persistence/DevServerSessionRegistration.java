package com.rush.rushaicodemother.service.devserver.persistence;

import java.nio.file.Path;

/** Data required to atomically reserve a durable Dev Server session. */
public record DevServerSessionRegistration(
        Long appId,
        Long userId,
        String nodeId,
        String leaseOwner,
        Path projectDirectory,
        int port
) {
}
