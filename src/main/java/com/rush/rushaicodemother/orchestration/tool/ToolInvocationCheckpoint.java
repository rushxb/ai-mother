package com.rush.rushaicodemother.orchestration.tool;

import cn.hutool.crypto.digest.DigestUtil;

import java.time.Instant;

/** Immutable checkpoint for the exact model tool invocation that is waiting for a decision. */
public record ToolInvocationCheckpoint(
        int schemaVersion,
        String requestId,
        String toolName,
        String argumentsJson,
        String runtimeStateJson,
        Instant capturedAt
) {

    public static final int CURRENT_SCHEMA_VERSION = 1;

    public ToolInvocationCheckpoint {
        argumentsJson = argumentsJson == null ? "" : argumentsJson;
        runtimeStateJson = runtimeStateJson == null ? "" : runtimeStateJson;
    }

    public String argumentsDigest() {
        return DigestUtil.sha256Hex(argumentsJson);
    }
}
