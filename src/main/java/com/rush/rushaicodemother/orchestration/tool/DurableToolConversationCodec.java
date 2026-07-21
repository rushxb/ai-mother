package com.rush.rushaicodemother.orchestration.tool;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.JacksonChatMessageJsonCodec;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Serializes and verifies the bounded conversation needed for durable tool continuation. */
@Component
public class DurableToolConversationCodec {

    static final int DEFAULT_MAX_MESSAGES = 12;
    static final int DEFAULT_MAX_MESSAGE_BYTES = 96 * 1024;
    static final int DEFAULT_MAX_TRANSCRIPT_BYTES = 128 * 1024;
    private static final int MAX_SOURCE_MESSAGES = 128;

    private final JacksonChatMessageJsonCodec messageCodec = new JacksonChatMessageJsonCodec();
    private final int maxMessages;
    private final int maxMessageBytes;
    private final int maxTranscriptBytes;

    public DurableToolConversationCodec() {
        this(DEFAULT_MAX_MESSAGES, DEFAULT_MAX_MESSAGE_BYTES, DEFAULT_MAX_TRANSCRIPT_BYTES);
    }

    DurableToolConversationCodec(int maxMessages,
                                 int maxMessageBytes,
                                 int maxTranscriptBytes) {
        if (maxMessages <= 0 || maxMessageBytes <= 0
                || maxTranscriptBytes < maxMessageBytes) {
            throw new IllegalArgumentException("durable tool conversation limits are invalid");
        }
        this.maxMessages = maxMessages;
        this.maxMessageBytes = maxMessageBytes;
        this.maxTranscriptBytes = maxTranscriptBytes;
    }

    public DurableToolConversation capture(List<ChatMessage> messages,
                                           String requestId,
                                           String toolName,
                                           String argumentsJson) {
        return capture(messages, requestId, toolName, argumentsJson, null);
    }

    public DurableToolConversation capture(List<ChatMessage> messages,
                                           String requestId,
                                           String toolName,
                                           String argumentsJson,
                                           UserMessage currentUserMessage) {
        List<ChatMessage> sourceMessages = requireSourceMessages(messages);
        int interruptedRequestIndex = validateInterruptedRequest(
                sourceMessages, requestId, toolName, argumentsJson);
        List<SerializedMessage> selectedMessages = selectContinuationWindow(
                sourceMessages, interruptedRequestIndex, currentUserMessage);
        List<String> messagesJson = new ArrayList<>(selectedMessages.size());
        int totalBytes = 0;
        for (SerializedMessage message : selectedMessages) {
            totalBytes = Math.addExact(totalBytes, message.bytes());
            messagesJson.add(message.json());
        }
        String normalizedRequestId = requireText(requestId, "tool request id");
        return new DurableToolConversation(
                DurableToolConversation.CURRENT_SCHEMA_VERSION,
                messagesJson,
                messagesJson.size(),
                totalBytes,
                digest(normalizedRequestId, messagesJson, totalBytes),
                normalizedRequestId
        );
    }

    public List<ChatMessage> restore(DurableToolConversation conversation,
                                     ToolInvocationCheckpoint checkpoint) {
        if (checkpoint == null) {
            throw new IllegalArgumentException("tool invocation checkpoint is required");
        }
        return restore(
                conversation,
                checkpoint.requestId(),
                checkpoint.toolName(),
                checkpoint.argumentsJson()
        );
    }

    List<ChatMessage> restore(DurableToolConversation conversation,
                              String requestId,
                              String toolName,
                              String argumentsJson) {
        if (conversation == null
                || conversation.schemaVersion() != DurableToolConversation.CURRENT_SCHEMA_VERSION
                || conversation.messagesJson().isEmpty()
                || conversation.messagesJson().size() > maxMessages
                || conversation.messageCount() != conversation.messagesJson().size()
                || conversation.totalBytes() <= 0
                || conversation.totalBytes() > maxTranscriptBytes
                || !Objects.equals(conversation.interruptedRequestId(), requestId)
                || conversation.digest() == null
                || !conversation.digest().matches("[a-f0-9]{64}")) {
            throw new IllegalStateException("tool continuation transcript metadata is invalid");
        }
        List<ChatMessage> messages = new ArrayList<>(conversation.messagesJson().size());
        int totalBytes = 0;
        for (String json : conversation.messagesJson()) {
            int messageBytes = utf8Length(json);
            if (messageBytes <= 0 || messageBytes > maxMessageBytes) {
                throw new IllegalStateException("tool continuation transcript message is invalid");
            }
            totalBytes = Math.addExact(totalBytes, messageBytes);
            if (totalBytes > maxTranscriptBytes) {
                throw new IllegalStateException("tool continuation transcript exceeds the limit");
            }
            ChatMessage message;
            try {
                message = messageCodec.messageFromJson(json);
            } catch (RuntimeException malformedMessage) {
                throw new IllegalStateException(
                        "tool continuation transcript cannot be restored", malformedMessage);
            }
            requireSupportedMessage(message);
            messages.add(message);
        }
        if (totalBytes != conversation.totalBytes()
                || !constantTimeEquals(
                        conversation.digest(),
                        digest(conversation.interruptedRequestId(),
                                conversation.messagesJson(), totalBytes))) {
            throw new IllegalStateException("tool continuation transcript integrity check failed");
        }
        validateInterruptedRequest(messages, requestId, toolName, argumentsJson);
        return List.copyOf(messages);
    }

    private List<ChatMessage> requireSourceMessages(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty() || messages.size() > MAX_SOURCE_MESSAGES) {
            throw new IllegalStateException("bounded tool continuation transcript is required");
        }
        return List.copyOf(messages);
    }

    private int validateInterruptedRequest(List<ChatMessage> messages,
                                           String requestId,
                                           String toolName,
                                           String argumentsJson) {
        String requiredRequestId = requireText(requestId, "tool request id");
        String requiredToolName = requireText(toolName, "tool name");
        String requiredArguments = argumentsJson == null ? "" : argumentsJson;
        int matchingRequestIndex = -1;
        int requestIdOccurrences = 0;
        for (int messageIndex = 0; messageIndex < messages.size(); messageIndex++) {
            ChatMessage message = messages.get(messageIndex);
            if (!(message instanceof AiMessage aiMessage) || !aiMessage.hasToolExecutionRequests()) {
                continue;
            }
            for (ToolExecutionRequest request : aiMessage.toolExecutionRequests()) {
                if (!Objects.equals(requiredRequestId, request.id())) {
                    continue;
                }
                requestIdOccurrences++;
                if (Objects.equals(requiredToolName, request.name())
                        && Objects.equals(requiredArguments,
                                request.arguments() == null ? "" : request.arguments())) {
                    matchingRequestIndex = messageIndex;
                }
            }
        }
        if (requestIdOccurrences != 1 || matchingRequestIndex < 0) {
            throw new IllegalStateException(
                    "interrupted tool request does not match the continuation transcript");
        }
        AiMessage interruptedMessage = (AiMessage) messages.get(matchingRequestIndex);
        Set<String> siblingRequestIds = new HashSet<>();
        for (ToolExecutionRequest request : interruptedMessage.toolExecutionRequests()) {
            if (request.id() == null || request.id().isBlank() || !siblingRequestIds.add(request.id())) {
                throw new IllegalStateException("interrupted tool round contains an invalid request id");
            }
        }
        Set<String> completedRequestIds = new HashSet<>();
        for (int index = matchingRequestIndex + 1; index < messages.size(); index++) {
            if (!(messages.get(index) instanceof ToolExecutionResultMessage result)) {
                throw new IllegalStateException(
                        "interrupted tool round contains a message after the unresolved request");
            }
            if (!siblingRequestIds.contains(result.id()) || !completedRequestIds.add(result.id())) {
                throw new IllegalStateException(
                        "interrupted tool round contains an invalid completed sibling request");
            }
            if (Objects.equals(requiredRequestId, result.id())) {
                throw new IllegalStateException("interrupted tool request is already resolved");
            }
        }
        return matchingRequestIndex;
    }

    private List<SerializedMessage> selectContinuationWindow(List<ChatMessage> messages,
                                                              int interruptedRequestIndex,
                                                              UserMessage currentUserMessage) {
        int systemIndex = lastIndexOf(messages, SystemMessage.class, interruptedRequestIndex);
        int userIndex = lastIndexOf(messages, UserMessage.class, interruptedRequestIndex);
        UserMessage userAnchor = currentUserMessage != null
                ? currentUserMessage
                : userIndex < 0 ? null : (UserMessage) messages.get(userIndex);

        List<SerializedMessage> systemAnchor = systemIndex < 0
                ? List.of()
                : List.of(serializeRequired(messages.get(systemIndex)));
        List<SerializedMessage> userAnchorMessages = userAnchor == null
                ? List.of()
                : List.of(serializeRequired(userAnchor));
        List<SerializedMessage> pendingSuffix = serializeRequired(
                messages.subList(interruptedRequestIndex, messages.size()));

        int requiredCount = systemAnchor.size() + userAnchorMessages.size() + pendingSuffix.size();
        int requiredBytes = totalBytes(systemAnchor) + totalBytes(userAnchorMessages) + totalBytes(pendingSuffix);
        if (requiredCount > maxMessages || requiredBytes > maxTranscriptBytes) {
            throw new IllegalStateException("required tool continuation transcript exceeds the limit");
        }

        int historyStart = Math.max(systemIndex, userIndex) + 1;
        List<List<ChatMessage>> historyGroups = completedHistoryGroups(
                messages, Math.max(0, historyStart), interruptedRequestIndex);
        List<List<SerializedMessage>> selectedGroups = new ArrayList<>();
        int selectedCount = requiredCount;
        int selectedBytes = requiredBytes;
        for (int index = historyGroups.size() - 1; index >= 0; index--) {
            List<SerializedMessage> group = serializeOptional(historyGroups.get(index));
            if (group.isEmpty()) {
                break;
            }
            int groupBytes = totalBytes(group);
            if (selectedCount + group.size() > maxMessages
                    || selectedBytes + groupBytes > maxTranscriptBytes) {
                break;
            }
            selectedGroups.add(0, group);
            selectedCount += group.size();
            selectedBytes += groupBytes;
        }

        List<SerializedMessage> selected = new ArrayList<>(selectedCount);
        selected.addAll(systemAnchor);
        selected.addAll(userAnchorMessages);
        selectedGroups.forEach(selected::addAll);
        selected.addAll(pendingSuffix);
        return List.copyOf(selected);
    }

    private List<List<ChatMessage>> completedHistoryGroups(List<ChatMessage> messages,
                                                            int startIndex,
                                                            int endIndex) {
        List<List<ChatMessage>> groups = new ArrayList<>();
        int index = startIndex;
        while (index < endIndex) {
            ChatMessage message = messages.get(index);
            if (message instanceof SystemMessage || message instanceof UserMessage) {
                index++;
                continue;
            }
            if (!(message instanceof AiMessage aiMessage)) {
                throw new IllegalStateException("tool continuation history contains an orphan result");
            }
            List<ChatMessage> group = new ArrayList<>();
            group.add(aiMessage);
            index++;
            while (index < endIndex && messages.get(index) instanceof ToolExecutionResultMessage) {
                group.add(messages.get(index));
                index++;
            }
            validateCompletedHistoryGroup(aiMessage, group);
            groups.add(List.copyOf(group));
        }
        return List.copyOf(groups);
    }

    private void validateCompletedHistoryGroup(AiMessage aiMessage, List<ChatMessage> group) {
        if (!aiMessage.hasToolExecutionRequests()) {
            if (group.size() != 1) {
                throw new IllegalStateException("tool continuation history contains an orphan result");
            }
            return;
        }
        Set<String> expectedRequestIds = new HashSet<>();
        for (ToolExecutionRequest request : aiMessage.toolExecutionRequests()) {
            if (request.id() == null || request.id().isBlank() || !expectedRequestIds.add(request.id())) {
                throw new IllegalStateException("tool continuation history contains an invalid request id");
            }
        }
        Set<String> completedRequestIds = new HashSet<>();
        for (int index = 1; index < group.size(); index++) {
            ToolExecutionResultMessage result = (ToolExecutionResultMessage) group.get(index);
            if (!expectedRequestIds.contains(result.id()) || !completedRequestIds.add(result.id())) {
                throw new IllegalStateException("tool continuation history contains an invalid result");
            }
        }
        if (!completedRequestIds.equals(expectedRequestIds)) {
            throw new IllegalStateException("tool continuation history contains an incomplete tool round");
        }
    }

    private int lastIndexOf(List<ChatMessage> messages,
                            Class<? extends ChatMessage> messageType,
                            int beforeIndex) {
        for (int index = beforeIndex - 1; index >= 0; index--) {
            if (messageType.isInstance(messages.get(index))) {
                return index;
            }
        }
        return -1;
    }

    private List<SerializedMessage> serializeRequired(List<ChatMessage> messages) {
        List<SerializedMessage> serialized = new ArrayList<>(messages.size());
        for (ChatMessage message : messages) {
            serialized.add(serializeRequired(message));
        }
        return List.copyOf(serialized);
    }

    private List<SerializedMessage> serializeOptional(List<ChatMessage> messages) {
        List<SerializedMessage> serialized = new ArrayList<>(messages.size());
        for (ChatMessage message : messages) {
            SerializedMessage item = serialize(message, false);
            if (item == null) {
                return List.of();
            }
            serialized.add(item);
        }
        return List.copyOf(serialized);
    }

    private SerializedMessage serializeRequired(ChatMessage message) {
        SerializedMessage serialized = serialize(message, true);
        if (serialized == null) {
            throw new IllegalStateException("tool continuation transcript message exceeds the limit");
        }
        return serialized;
    }

    private SerializedMessage serialize(ChatMessage message, boolean required) {
        requireSupportedMessage(message);
        String json;
        try {
            json = messageCodec.messageToJson(message);
        } catch (RuntimeException serializationFailure) {
            throw new IllegalStateException(
                    "tool continuation transcript cannot be serialized", serializationFailure);
        }
        int messageBytes = utf8Length(json);
        if (messageBytes <= 0 || messageBytes > maxMessageBytes) {
            if (required) {
                throw new IllegalStateException("tool continuation transcript message exceeds the limit");
            }
            return null;
        }
        return new SerializedMessage(json, messageBytes);
    }

    private int totalBytes(List<SerializedMessage> messages) {
        int total = 0;
        for (SerializedMessage message : messages) {
            total = Math.addExact(total, message.bytes());
        }
        return total;
    }

    private void requireSupportedMessage(ChatMessage message) {
        if (!(message instanceof SystemMessage)
                && !(message instanceof UserMessage)
                && !(message instanceof AiMessage)
                && !(message instanceof ToolExecutionResultMessage)) {
            throw new IllegalStateException("tool continuation transcript contains an unsupported message type");
        }
    }

    private String digest(String requestId, List<String> messagesJson, int totalBytes) {
        MessageDigest digest = sha256();
        updateInt(digest, DurableToolConversation.CURRENT_SCHEMA_VERSION);
        updateBytes(digest, requestId.getBytes(StandardCharsets.UTF_8));
        updateInt(digest, messagesJson.size());
        updateInt(digest, totalBytes);
        for (String messageJson : messagesJson) {
            updateBytes(digest, messageJson.getBytes(StandardCharsets.UTF_8));
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private void updateBytes(MessageDigest digest, byte[] value) {
        updateInt(digest, value.length);
        digest.update(value);
    }

    private void updateInt(MessageDigest digest, int value) {
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(value).array());
    }

    private MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private int utf8Length(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    private boolean constantTimeEquals(String left, String right) {
        return MessageDigest.isEqual(
                left.getBytes(StandardCharsets.US_ASCII),
                right.getBytes(StandardCharsets.US_ASCII));
    }

    private String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value;
    }

    private record SerializedMessage(String json, int bytes) {
    }
}
