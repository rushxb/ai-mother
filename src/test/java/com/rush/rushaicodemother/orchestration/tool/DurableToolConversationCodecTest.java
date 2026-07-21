package com.rush.rushaicodemother.orchestration.tool;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DurableToolConversationCodecTest {

    private static final Instant NOW = Instant.parse("2026-07-16T12:00:00Z");

    @Test
    void roundTripMustPreserveThinkingAndInterruptedToolRequest() {
        ToolExecutionRequest request = request("call-1", "manageSnapshot", "{\"action\":\"rollbackSnapshot\"}");
        AiMessage assistant = AiMessage.builder()
                .text("I need approval before continuing.")
                .thinking("The requested rollback is destructive.")
                .toolExecutionRequests(List.of(request))
                .build();
        List<ChatMessage> messages = List.of(UserMessage.from("rollback the project"), assistant);
        DurableToolConversationCodec codec = new DurableToolConversationCodec();

        DurableToolConversation conversation = codec.capture(
                messages, request.id(), request.name(), request.arguments());
        List<ChatMessage> restored = codec.restore(conversation, checkpoint(request));

        AiMessage restoredAssistant = assertInstanceOf(AiMessage.class, restored.get(1));
        assertEquals(assistant.text(), restoredAssistant.text());
        assertEquals(assistant.thinking(), restoredAssistant.thinking());
        assertEquals(assistant.toolExecutionRequests(), restoredAssistant.toolExecutionRequests());
    }

    @Test
    void roundTripMustAllowAWindowWhereTheOriginalUserMessageWasEvicted() {
        ToolExecutionRequest request = request("call-1", "manageSnapshot", "{}");
        DurableToolConversationCodec codec = new DurableToolConversationCodec();

        DurableToolConversation conversation = codec.capture(
                List.of(AiMessage.builder().toolExecutionRequests(List.of(request)).build()),
                request.id(), request.name(), request.arguments());

        assertEquals(1, codec.restore(conversation, checkpoint(request)).size());
    }

    @Test
    void restoreMustRejectTranscriptTampering() {
        ToolExecutionRequest request = request("call-1", "manageSnapshot", "{\"action\":\"rollbackSnapshot\"}");
        DurableToolConversationCodec codec = new DurableToolConversationCodec();
        DurableToolConversation captured = codec.capture(
                List.of(
                        UserMessage.from("rollback the project"),
                        AiMessage.builder().toolExecutionRequests(List.of(request)).build()
                ),
                request.id(), request.name(), request.arguments());
        List<String> tamperedJson = new ArrayList<>(captured.messagesJson());
        tamperedJson.set(0, tamperedJson.get(0).replace("rollback", "rollbacx"));
        DurableToolConversation tampered = new DurableToolConversation(
                captured.schemaVersion(), tamperedJson, captured.messageCount(),
                captured.totalBytes(), captured.digest(), captured.interruptedRequestId());

        assertThrows(IllegalStateException.class,
                () -> codec.restore(tampered, checkpoint(request)));
    }

    @Test
    void restoreMustRejectDifferentToolArguments() {
        ToolExecutionRequest request = request("call-1", "manageSnapshot", "{\"action\":\"rollbackSnapshot\"}");
        DurableToolConversationCodec codec = new DurableToolConversationCodec();
        DurableToolConversation captured = codec.capture(
                List.of(
                        UserMessage.from("rollback the project"),
                        AiMessage.builder().toolExecutionRequests(List.of(request)).build()
                ),
                request.id(), request.name(), request.arguments());
        ToolExecutionRequest different = request(
                request.id(), request.name(), "{\"action\":\"deleteSnapshot\"}");

        assertThrows(IllegalStateException.class,
                () -> codec.restore(captured, checkpoint(different)));
    }

    @Test
    void captureMustRejectDuplicateInterruptedRequestIds() {
        ToolExecutionRequest first = request("call-1", "manageSnapshot", "{\"action\":\"rollbackSnapshot\"}");
        ToolExecutionRequest duplicate = request("call-1", "manageSnapshot", "{\"action\":\"listSnapshots\"}");
        DurableToolConversationCodec codec = new DurableToolConversationCodec();

        assertThrows(IllegalStateException.class, () -> codec.capture(
                List.of(
                        UserMessage.from("rollback the project"),
                        AiMessage.builder().toolExecutionRequests(List.of(first, duplicate)).build()
                ),
                first.id(), first.name(), first.arguments()));
    }

    @Test
    void captureMustRejectOversizedTranscript() {
        ToolExecutionRequest request = request("call-1", "manageSnapshot", "{}");
        DurableToolConversationCodec codec = new DurableToolConversationCodec(4, 64, 128);

        assertThrows(IllegalStateException.class, () -> codec.capture(
                List.of(
                        UserMessage.from("x".repeat(128)),
                        AiMessage.builder().toolExecutionRequests(List.of(request)).build()
                ),
                request.id(), request.name(), request.arguments()));
    }

    @Test
    void boundedWindowKeepsSystemCurrentUserRecentToolRoundAndPendingSuffix() {
        ToolExecutionRequest completed = request("call-complete", "readProject", "{}");
        ToolExecutionRequest sibling = request("call-sibling", "readProject", "{\"path\":\"README.md\"}");
        ToolExecutionRequest pending = request("call-pending", "manageSnapshot", "{}");
        UserMessage currentUser = UserMessage.from("continue the current dashboard task");
        List<ChatMessage> source = List.of(
                SystemMessage.from("system rules"),
                UserMessage.from("obsolete request"),
                AiMessage.from("obsolete response"),
                currentUser,
                AiMessage.builder().toolExecutionRequests(List.of(completed)).build(),
                ToolExecutionResultMessage.from(completed, "project files"),
                AiMessage.builder().toolExecutionRequests(List.of(sibling, pending)).build(),
                ToolExecutionResultMessage.from(sibling, "readme contents")
        );
        DurableToolConversationCodec codec = new DurableToolConversationCodec(6, 4_096, 32_768);

        DurableToolConversation conversation = codec.capture(
                source, pending.id(), pending.name(), pending.arguments(), currentUser);
        List<ChatMessage> restored = codec.restore(conversation, checkpoint(pending));

        assertEquals(6, restored.size());
        assertEquals(SystemMessage.from("system rules"), restored.get(0));
        assertEquals(currentUser, restored.get(1));
        assertEquals(source.get(4), restored.get(2));
        assertEquals(source.get(5), restored.get(3));
        assertEquals(source.get(6), restored.get(4));
        assertEquals(source.get(7), restored.get(5));
    }

    @Test
    void invocationUserAnchorIsAddedWhenMessageWindowAlreadyEvictedIt() {
        ToolExecutionRequest completed = request("call-complete", "readProject", "{}");
        ToolExecutionRequest pending = request("call-pending", "manageSnapshot", "{}");
        UserMessage invocationUser = UserMessage.from("authoritative enhanced generation prompt");
        List<ChatMessage> source = List.of(
                SystemMessage.from("system rules"),
                AiMessage.builder().toolExecutionRequests(List.of(completed)).build(),
                ToolExecutionResultMessage.from(completed, "project files"),
                AiMessage.builder().toolExecutionRequests(List.of(pending)).build()
        );
        DurableToolConversationCodec codec = new DurableToolConversationCodec(6, 4_096, 32_768);

        DurableToolConversation conversation = codec.capture(
                source, pending.id(), pending.name(), pending.arguments(), invocationUser);
        List<ChatMessage> restored = codec.restore(conversation, checkpoint(pending));

        assertEquals(List.of(
                source.get(0), invocationUser, source.get(1), source.get(2), source.get(3)), restored);
    }

    @Test
    void requiredPendingToolSuffixMustFailClosedWhenItCannotFit() {
        ToolExecutionRequest first = request("call-1", "readProject", "{}");
        ToolExecutionRequest second = request("call-2", "readProject", "{}");
        ToolExecutionRequest pending = request("call-pending", "manageSnapshot", "{}");
        DurableToolConversationCodec codec = new DurableToolConversationCodec(4, 4_096, 32_768);
        List<ChatMessage> source = List.of(
                SystemMessage.from("system rules"),
                AiMessage.builder().toolExecutionRequests(List.of(first, second, pending)).build(),
                ToolExecutionResultMessage.from(first, "first result"),
                ToolExecutionResultMessage.from(second, "second result")
        );

        assertThrows(IllegalStateException.class, () -> codec.capture(
                source,
                pending.id(),
                pending.name(),
                pending.arguments(),
                UserMessage.from("current request")
        ));
    }

    @Test
    void unresolvedToolRoundRejectsLaterNonResultMessages() {
        ToolExecutionRequest pending = request("call-pending", "manageSnapshot", "{}");
        DurableToolConversationCodec codec = new DurableToolConversationCodec();

        assertThrows(IllegalStateException.class, () -> codec.capture(
                List.of(
                        AiMessage.builder().toolExecutionRequests(List.of(pending)).build(),
                        UserMessage.from("a later turn must not exist")
                ),
                pending.id(), pending.name(), pending.arguments()
        ));
    }

    private ToolExecutionRequest request(String id, String name, String arguments) {
        return ToolExecutionRequest.builder()
                .id(id)
                .name(name)
                .arguments(arguments)
                .build();
    }

    private ToolInvocationCheckpoint checkpoint(ToolExecutionRequest request) {
        return new ToolInvocationCheckpoint(
                ToolInvocationCheckpoint.CURRENT_SCHEMA_VERSION,
                request.id(), request.name(), request.arguments(), "{}", NOW);
    }
}
