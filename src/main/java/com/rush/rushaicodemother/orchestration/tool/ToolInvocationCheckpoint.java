package com.rush.rushaicodemother.orchestration.tool;

import cn.hutool.crypto.digest.DigestUtil;

import java.time.Instant;

/** 等待决策的确切模型工具调用的不可变检查点。 */
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
