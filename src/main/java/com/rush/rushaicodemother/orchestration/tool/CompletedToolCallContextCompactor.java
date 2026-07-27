package com.rush.rushaicodemother.orchestration.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rush.rushaicodemother.config.ChatMemoryProperties;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 在下一轮模型请求中压缩已成功落盘的超大写工具参数，避免重复发送整份源码。
 * 原始对话记忆、未完成调用和失败调用保持不变，供执行校验、审批恢复与问题修正使用。
 */
@Component
public class CompletedToolCallContextCompactor {

    private static final String OMITTED_CONTENT = "[内容已写入工作区；如需查看请调用读取工具]";
    private static final Set<String> SOURCE_HEAVY_TOOLS = Set.of(
            "writeFile", "writeFiles", "modifyFile"
    );

    private final ObjectMapper objectMapper;
    private final int maximumArgumentsChars;

    public CompletedToolCallContextCompactor(ObjectMapper objectMapper,
                                             ChatMemoryProperties properties) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "JSON 解析器不能为空");
        Objects.requireNonNull(properties, "对话记忆配置不能为空");
        this.maximumArgumentsChars = properties.getCompletedToolArgumentsMaxChars();
        if (maximumArgumentsChars < 1_024 || maximumArgumentsChars > 262_144) {
            throw new IllegalArgumentException("已完成工具参数压缩阈值无效");
        }
    }

    public ChatRequest compact(ChatRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("模型请求不能为空");
        }
        List<ChatMessage> compactedMessages = compact(request.messages());
        if (compactedMessages == request.messages()) {
            return request;
        }
        return request.toBuilder().messages(compactedMessages).build();
    }

    List<ChatMessage> compact(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return messages;
        }
        List<ChatMessage> compacted = new ArrayList<>(messages.size());
        boolean changed = false;
        for (int messageIndex = 0; messageIndex < messages.size(); messageIndex++) {
            ChatMessage message = messages.get(messageIndex);
            if (!(message instanceof AiMessage aiMessage) || !aiMessage.hasToolExecutionRequests()) {
                compacted.add(message);
                continue;
            }
            Set<String> successfulRequestIds = successfulRequestIds(messages, messageIndex + 1);
            List<ToolExecutionRequest> requests = new ArrayList<>(
                    aiMessage.toolExecutionRequests().size());
            boolean messageChanged = false;
            for (ToolExecutionRequest request : aiMessage.toolExecutionRequests()) {
                ToolExecutionRequest compactedRequest = compactRequest(request, successfulRequestIds);
                requests.add(compactedRequest);
                messageChanged = messageChanged || compactedRequest != request;
            }
            if (messageChanged) {
                compacted.add(aiMessage.toBuilder().toolExecutionRequests(requests).build());
                changed = true;
            } else {
                compacted.add(message);
            }
        }
        return changed ? List.copyOf(compacted) : messages;
    }

    private Set<String> successfulRequestIds(List<ChatMessage> messages, int resultStartIndex) {
        Set<String> requestIds = new HashSet<>();
        for (int index = resultStartIndex; index < messages.size(); index++) {
            if (!(messages.get(index) instanceof ToolExecutionResultMessage result)) {
                break;
            }
            if (result.id() != null && !result.id().isBlank()
                    && Boolean.FALSE.equals(result.isError())) {
                requestIds.add(result.id());
            }
        }
        return requestIds;
    }

    private ToolExecutionRequest compactRequest(ToolExecutionRequest request,
                                                Set<String> successfulRequestIds) {
        if (request == null
                || request.id() == null
                || !successfulRequestIds.contains(request.id())
                || !SOURCE_HEAVY_TOOLS.contains(request.name())
                || request.arguments() == null
                || request.arguments().length() <= maximumArgumentsChars) {
            return request;
        }
        String compactedArguments = compactArguments(request.name(), request.arguments());
        if (compactedArguments == null || compactedArguments.equals(request.arguments())) {
            return request;
        }
        return request.toBuilder().arguments(compactedArguments).build();
    }

    private String compactArguments(String toolName, String arguments) {
        try {
            JsonNode parsed = objectMapper.readTree(arguments);
            if (!(parsed instanceof ObjectNode source)) {
                return null;
            }
            ObjectNode compacted = switch (toolName) {
                case "writeFile" -> compactWriteFile(source);
                case "writeFiles" -> compactWriteFiles(source);
                case "modifyFile" -> compactModifyFile(source);
                default -> null;
            };
            return compacted == null ? null : objectMapper.writeValueAsString(compacted);
        } catch (JsonProcessingException malformedArguments) {
            return null;
        }
    }

    private ObjectNode compactWriteFile(ObjectNode source) {
        String relativeFilePath = requiredText(source, "relativeFilePath");
        if (relativeFilePath == null || !source.has("content")) {
            return null;
        }
        ObjectNode compacted = objectMapper.createObjectNode();
        compacted.put("relativeFilePath", relativeFilePath);
        compacted.put("content", OMITTED_CONTENT);
        return compacted;
    }

    private ObjectNode compactWriteFiles(ObjectNode source) {
        JsonNode filesNode = source.get("files");
        if (!(filesNode instanceof ArrayNode sourceFiles) || sourceFiles.isEmpty()) {
            return null;
        }
        ArrayNode compactedFiles = objectMapper.createArrayNode();
        for (JsonNode fileNode : sourceFiles) {
            if (!(fileNode instanceof ObjectNode file)) {
                return null;
            }
            String relativeFilePath = requiredText(file, "relativeFilePath");
            if (relativeFilePath == null || !file.has("content")) {
                return null;
            }
            ObjectNode compactedFile = objectMapper.createObjectNode();
            compactedFile.put("relativeFilePath", relativeFilePath);
            compactedFile.put("content", OMITTED_CONTENT);
            compactedFiles.add(compactedFile);
        }
        ObjectNode compacted = objectMapper.createObjectNode();
        compacted.set("files", compactedFiles);
        return compacted;
    }

    private ObjectNode compactModifyFile(ObjectNode source) {
        String relativeFilePath = requiredText(source, "relativeFilePath");
        if (relativeFilePath == null || !source.has("oldContent") || !source.has("newContent")) {
            return null;
        }
        ObjectNode compacted = objectMapper.createObjectNode();
        compacted.put("relativeFilePath", relativeFilePath);
        compacted.put("oldContent", OMITTED_CONTENT);
        compacted.put("newContent", OMITTED_CONTENT);
        return compacted;
    }

    private String requiredText(ObjectNode source, String fieldName) {
        JsonNode value = source.get(fieldName);
        return value != null && value.isTextual() && !value.textValue().isBlank()
                ? value.textValue()
                : null;
    }
}
