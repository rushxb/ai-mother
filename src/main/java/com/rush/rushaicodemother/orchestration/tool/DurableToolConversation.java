package com.rush.rushaicodemother.orchestration.tool;

import java.util.List;

/** Exact, integrity-protected chat transcript required to resume an interrupted tool round. */
public record DurableToolConversation(
        int schemaVersion,
        List<String> messagesJson,
        int messageCount,
        int totalBytes,
        String digest,
        String interruptedRequestId
) {
    public static final int CURRENT_SCHEMA_VERSION = 1;

    public DurableToolConversation {
        messagesJson = messagesJson == null ? List.of() : List.copyOf(messagesJson);
        digest = digest == null ? "" : digest;
        interruptedRequestId = interruptedRequestId == null ? "" : interruptedRequestId;
    }
}
