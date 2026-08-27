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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 在下一轮模型请求中压缩已成功落盘的超大写工具参数，避免重复发送整份源码。
 * 原始对话记忆、未完成调用和失败调用保持不变，供执行校验、审批恢复与问题修正使用。
 */
@Component
public class CompletedToolCallContextCompactor {

    private static final String CURRENT_WORKSPACE_CONTENT =
            "[目标内容已在工作区确认；如需查看请调用读取工具]";
    private static final String ROLLED_BACK_CONTENT =
            "历史写入内容已因后续回滚失去当前性；请重新读取确认";
    private static final Set<String> SOURCE_HEAVY_TOOLS = Set.of(
            "writeFile", "writeFiles", "modifyFile"
    );

    private final ObjectMapper objectMapper;
    private final int maximumArgumentsChars;

    /**
 * 创建完成工具调用上下文{@code Compactor}实例并完成必要的依赖和初始状态设置。
 *
 * @param objectMapper {@code objectMapper} 对应的调用参数
 * @param properties 配置属性
 */
    public CompletedToolCallContextCompactor(ObjectMapper objectMapper,
                                             ChatMemoryProperties properties) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "JSON 解析器不能为空");
        Objects.requireNonNull(properties, "对话记忆配置不能为空");
        this.maximumArgumentsChars = properties.getCompletedToolArgumentsMaxChars();
        if (maximumArgumentsChars < 1_024 || maximumArgumentsChars > 262_144) {
            throw new IllegalArgumentException("已完成工具参数压缩阈值无效");
        }
    }

    /**
 * 压缩完成工具调用上下文{@code Compactor}以满足资源限制。
 *
 * @param request 请求参数
 * @return 完成工具调用上下文{@code Compactor}
 */
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

    /** 压缩完成工具调用上下文{@code Compactor}以满足资源限制。 */
    List<ChatMessage> compact(List<ChatMessage> messages) {
        // 先处理前置条件和快速返回分支，避免无效输入进入核心流程。
        if (messages == null || messages.isEmpty()) {
            return messages;
        }
        List<ChatMessage> compacted = new ArrayList<>(messages.size());
        boolean changed = false;
        int lastWorkspaceInvalidationIndex = lastWorkspaceInvalidationIndex(messages);
        // 按既定顺序逐项处理，并在达到资源或状态边界时提前结束。
        for (int messageIndex = 0; messageIndex < messages.size(); messageIndex++) {
            ChatMessage message = messages.get(messageIndex);
            if (!(message instanceof AiMessage aiMessage) || !aiMessage.hasToolExecutionRequests()) {
                compacted.add(message);
                continue;
            }
            Map<String, ConfirmedMutationResult> successfulResults =
                    successfulResults(messages, messageIndex + 1);
            List<ToolExecutionRequest> requests = new ArrayList<>(
                    aiMessage.toolExecutionRequests().size());
            boolean messageChanged = false;
            for (ToolExecutionRequest request : aiMessage.toolExecutionRequests()) {
                ToolExecutionRequest compactedRequest = compactRequest(
                        request, successfulResults, lastWorkspaceInvalidationIndex);
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

    /** 返回携带可信 mutation 事实的成功工具结果。 */
    private Map<String, ConfirmedMutationResult> successfulResults(
            List<ChatMessage> messages,
            int resultStartIndex
    ) {
        Map<String, ConfirmedMutationResult> results = new HashMap<>();
        for (int index = resultStartIndex; index < messages.size(); index++) {
            if (!(messages.get(index) instanceof ToolExecutionResultMessage result)) {
                break;
            }
            if (result.id() != null && !result.id().isBlank()
                    && Boolean.FALSE.equals(result.isError())
                    && ToolResultEvidence.hasEffectiveMutationEvidence(result)) {
                results.put(result.id(), new ConfirmedMutationResult(result, index));
            }
        }
        return results;
    }

    /** 压缩请求以满足资源限制。 */
    private ToolExecutionRequest compactRequest(ToolExecutionRequest request,
                                                Map<String, ConfirmedMutationResult> successfulResults,
                                                int lastWorkspaceInvalidationIndex) {
        if (request == null
                || request.id() == null
                || !SOURCE_HEAVY_TOOLS.contains(request.name())
                || request.arguments() == null
                || request.arguments().length() <= maximumArgumentsChars) {
            return request;
        }
        ConfirmedMutationResult confirmedResult = successfulResults.get(request.id());
        if (confirmedResult == null
                || !Objects.equals(request.name(), confirmedResult.message().toolName())) {
            return request;
        }
        String omittedContent = confirmedResult.messageIndex() < lastWorkspaceInvalidationIndex
                ? ROLLED_BACK_CONTENT
                : CURRENT_WORKSPACE_CONTENT;
        String compactedArguments = compactArguments(
                request.name(), request.arguments(), confirmedResult.message(), omittedContent);
        if (compactedArguments == null || compactedArguments.equals(request.arguments())) {
            return request;
        }
        return request.toBuilder().arguments(compactedArguments).build();
    }

    /** 压缩参数以满足资源限制。 */
    private String compactArguments(String toolName,
                                    String arguments,
                                    ToolExecutionResultMessage result,
                                    String omittedContent) {
        try {
            JsonNode parsed = objectMapper.readTree(arguments);
            if (!(parsed instanceof ObjectNode source)) {
                return null;
            }
            CompactionPlan plan = switch (toolName) {
                case "writeFile" -> compactWriteFile(source, omittedContent);
                case "writeFiles" -> compactWriteFiles(source, omittedContent);
                case "modifyFile" -> compactModifyFile(source, omittedContent);
                default -> null;
            };
            if (plan == null || !evidenceAuthorizesCompaction(plan.requestedPaths(), result)) {
                return null;
            }
            return objectMapper.writeValueAsString(plan.arguments());
        } catch (JsonProcessingException malformedArguments) {
            return null;
        }
    }

    /**
     * 非空证据必须至少命中本次请求；空证据仅在平台明确标记幂等完成时授权。
     * 路径合法性与规范化统一复用 {@link ToolResultEvidence}，避免形成第二套路由规则。
     */
    private boolean evidenceAuthorizesCompaction(
            List<String> requestedPaths,
            ToolExecutionResultMessage result
    ) {
        List<String> normalizedRequestedPaths = ToolResultEvidence.retainRequestedPaths(
                requestedPaths, requestedPaths);
        if (normalizedRequestedPaths.isEmpty()) {
            return false;
        }
        if (ToolResultEvidence.confirmsNoMutation(result)) {
            return true;
        }
        return !ToolResultEvidence.retainRequestedPaths(
                normalizedRequestedPaths,
                ToolResultEvidence.effectiveMutationPaths(result)
        ).isEmpty();
    }

    private CompactionPlan compactWriteFile(ObjectNode source, String omittedContent) {
        String relativeFilePath = requiredText(source, "relativeFilePath");
        if (relativeFilePath == null || !source.has("content")) {
            return null;
        }
        ObjectNode compacted = objectMapper.createObjectNode();
        compacted.put("relativeFilePath", relativeFilePath);
        compacted.put("content", omittedContent);
        return new CompactionPlan(compacted, List.of(relativeFilePath));
    }

    /** 压缩{@code Write}文件以满足资源限制。 */
    private CompactionPlan compactWriteFiles(ObjectNode source, String omittedContent) {
        JsonNode filesNode = source.get("files");
        if (!(filesNode instanceof ArrayNode sourceFiles) || sourceFiles.isEmpty()) {
            return null;
        }
        ArrayNode compactedFiles = objectMapper.createArrayNode();
        List<String> requestedPaths = new ArrayList<>(sourceFiles.size());
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
            compactedFile.put("content", omittedContent);
            compactedFiles.add(compactedFile);
            requestedPaths.add(relativeFilePath);
        }
        ObjectNode compacted = objectMapper.createObjectNode();
        compacted.set("files", compactedFiles);
        return new CompactionPlan(compacted, List.copyOf(requestedPaths));
    }

    private CompactionPlan compactModifyFile(ObjectNode source, String omittedContent) {
        String relativeFilePath = requiredText(source, "relativeFilePath");
        if (relativeFilePath == null || !source.has("oldContent") || !source.has("newContent")) {
            return null;
        }
        ObjectNode compacted = objectMapper.createObjectNode();
        compacted.put("relativeFilePath", relativeFilePath);
        compacted.put("oldContent", omittedContent);
        compacted.put("newContent", omittedContent);
        return new CompactionPlan(compacted, List.of(relativeFilePath));
    }

    private String requiredText(ObjectNode source, String fieldName) {
        JsonNode value = source.get(fieldName);
        return value != null && value.isTextual() && !value.textValue().isBlank()
                ? value.textValue()
                : null;
    }

    /**
     * 定位最后一次可信工作区回滚结果。
     *
     * <p>只接受同一工具轮内 requestId 与 toolName 均匹配的结果，
     * 防止错配或孤儿结果把历史写入误标为已回滚。</p>
     */
    private int lastWorkspaceInvalidationIndex(List<ChatMessage> messages) {
        int lastIndex = -1;
        for (int messageIndex = 0; messageIndex < messages.size(); messageIndex++) {
            if (!(messages.get(messageIndex) instanceof AiMessage aiMessage)
                    || !aiMessage.hasToolExecutionRequests()) {
                continue;
            }
            Map<String, ToolExecutionRequest> requestsById = new HashMap<>();
            for (ToolExecutionRequest request : aiMessage.toolExecutionRequests()) {
                if (request != null && request.id() != null) {
                    requestsById.put(request.id(), request);
                }
            }
            for (int resultIndex = messageIndex + 1; resultIndex < messages.size(); resultIndex++) {
                if (!(messages.get(resultIndex) instanceof ToolExecutionResultMessage result)) {
                    break;
                }
                ToolExecutionRequest request = requestsById.get(result.id());
                if (request != null
                        && Objects.equals(request.name(), result.toolName())
                        && ToolResultEvidence.confirmsWorkspaceInvalidation(result)) {
                    lastIndex = resultIndex;
                }
            }
        }
        return lastIndex;
    }

    /** 已完成参数校验、等待证据授权的压缩计划。 */
    private record CompactionPlan(ObjectNode arguments, List<String> requestedPaths) {
    }

    /** 成功 mutation 结果及其在上下文中的时序位置。 */
    private record ConfirmedMutationResult(
            ToolExecutionResultMessage message,
            int messageIndex
    ) {
    }
}
