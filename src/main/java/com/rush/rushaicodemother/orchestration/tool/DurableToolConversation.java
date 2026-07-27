package com.rush.rushaicodemother.orchestration.tool;

import java.util.List;

/** 恢复中断的工具回合需要精确的、受完整性保护的聊天记录。 */
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
