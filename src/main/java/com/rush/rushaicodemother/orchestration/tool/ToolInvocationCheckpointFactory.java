package com.rush.rushaicodemother.orchestration.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rush.rushaicodemother.ai.model.GenerationPerformanceProfile;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.GenerationSession;
import com.rush.rushaicodemother.orchestration.GenerationSessionRegistry;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionSnapshot;
import com.rush.rushaicodemother.orchestration.runtime.tracing.GenerationTraceContextBridge;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import dev.langchain4j.data.message.UserMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;

/** 在工具调用边界捕获不可变的延续检查点。 */
@Component
public class ToolInvocationCheckpointFactory {

    private final GenerationSessionRegistry sessionRegistry;
    private final ObjectMapper objectMapper;
    private final GenerationTraceContextBridge traceContextBridge;
    private final ChatMemoryStore chatMemoryStore;
    private final DurableToolConversationCodec conversationCodec;
    private final Clock clock;

    @Autowired
    public ToolInvocationCheckpointFactory(GenerationSessionRegistry sessionRegistry,
                                           ObjectMapper objectMapper,
                                           GenerationTraceContextBridge traceContextBridge,
                                           ChatMemoryStore chatMemoryStore,
                                           DurableToolConversationCodec conversationCodec) {
        this(sessionRegistry, objectMapper, traceContextBridge, chatMemoryStore,
                conversationCodec, Clock.systemUTC());
    }

    ToolInvocationCheckpointFactory(GenerationSessionRegistry sessionRegistry,
                                    ObjectMapper objectMapper,
                                    GenerationTraceContextBridge traceContextBridge,
                                    ChatMemoryStore chatMemoryStore,
                                    DurableToolConversationCodec conversationCodec,
                                    Clock clock) {
        this.sessionRegistry = sessionRegistry;
        this.objectMapper = objectMapper;
        this.traceContextBridge = traceContextBridge == null
                ? GenerationTraceContextBridge.NOOP
                : traceContextBridge;
        this.chatMemoryStore = chatMemoryStore;
        this.conversationCodec = conversationCodec;
        this.clock = clock;
    }

    public ToolInvocationCheckpoint capture(String taskId,
                                             String toolRequestId,
                                             String toolName,
                                             String argumentsJson,
                                             CodeGenTypeEnum codeGenType,
                                             GenerationPerformanceProfile profile) {
        return capture(taskId, toolRequestId, toolName, argumentsJson, codeGenType, profile, null);
    }

    public ToolInvocationCheckpoint capture(String taskId,
                                             String toolRequestId,
                                             String toolName,
                                             String argumentsJson,
                                             CodeGenTypeEnum codeGenType,
                                             GenerationPerformanceProfile profile,
                                             UserMessage currentUserMessage) {
        GenerationSession session = sessionRegistry.getByTaskId(taskId);
        if (session == null || session.preparation() == null || session.executionContext() == null
                || session.taskRequest() == null || session.taskRequest().message() == null
                || session.taskRequest().message().isBlank()) {
            throw new IllegalStateException("active generation session is required for tool continuation");
        }
        GenerationExecutionSnapshot execution = session.executionContext().snapshot();
        Instant capturedAt = clock.instant();
        DurableToolConversation durableConversation = conversationCodec.capture(
                chatMemoryStore.getMessages(execution.appId()),
                toolRequestId,
                toolName,
                argumentsJson,
                currentUserMessage
        );
        GenerationToolContinuationState state = new GenerationToolContinuationState(
                GenerationToolContinuationState.CURRENT_SCHEMA_VERSION,
                taskId,
                execution.appId(),
                execution.userId(),
                session.route(),
                session.taskRequest().message(),
                codeGenType,
                profile,
                session.preparation(),
                session.executionContext().limits(),
                execution,
                traceContextBridge.capture(),
                durableConversation,
                capturedAt
        );
        return new ToolInvocationCheckpoint(
                ToolInvocationCheckpoint.CURRENT_SCHEMA_VERSION,
                toolRequestId,
                toolName,
                argumentsJson,
                serialize(state),
                capturedAt
        );
    }

    public GenerationToolContinuationState restore(ToolInvocationCheckpoint checkpoint) {
        if (checkpoint == null || checkpoint.runtimeStateJson().isBlank()) {
            throw new IllegalArgumentException("tool invocation checkpoint is required");
        }
        try {
            GenerationToolContinuationState state = objectMapper.readValue(
                    checkpoint.runtimeStateJson(), GenerationToolContinuationState.class);
            if (!GenerationToolContinuationState.supportsSchemaVersion(state.schemaVersion())
                    || !checkpoint.capturedAt().equals(state.capturedAt())) {
                throw new IllegalStateException("tool continuation checkpoint version or timestamp is invalid");
            }
            if (state.schemaVersion() >= 3) {
                conversationCodec.restore(state.durableConversation(), checkpoint);
            } else if (state.durableConversation() != null) {
                throw new IllegalStateException("legacy tool continuation state contains unsupported transcript data");
            }
            return state;
        } catch (JsonProcessingException malformedState) {
            throw new IllegalStateException("tool continuation checkpoint cannot be restored", malformedState);
        }
    }

    private String serialize(GenerationToolContinuationState state) {
        try {
            return objectMapper.writeValueAsString(state);
        } catch (JsonProcessingException serializationFailure) {
            throw new IllegalStateException("tool continuation checkpoint cannot be serialized", serializationFailure);
        }
    }
}
