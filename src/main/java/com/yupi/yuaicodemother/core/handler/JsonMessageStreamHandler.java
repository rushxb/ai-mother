package com.yupi.yuaicodemother.core.handler;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.yupi.yuaicodemother.ai.model.message.*;
import com.yupi.yuaicodemother.ai.tools.BaseTool;
import com.yupi.yuaicodemother.ai.tools.ToolManager;
import com.yupi.yuaicodemother.core.error.GenerationErrorClassifier;
import com.yupi.yuaicodemother.model.entity.User;
import com.yupi.yuaicodemother.model.enums.ChatHistoryMessageTypeEnum;
import com.yupi.yuaicodemother.service.ChatHistoryService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * JSON 消息流处理器
 * 处理 VUE_PROJECT 类型的复杂流式响应，包含工具调用信息
 */
@Slf4j
@Component
public class JsonMessageStreamHandler {

    @Resource
    private ToolManager toolManager;

    /**
     * 处理 TokenStream（VUE_PROJECT）
     * 解析 JSON 消息并重组为完整的响应格式
     *
     * @param originFlux         原始流
     * @param chatHistoryService 聊天历史服务
     * @param appId              应用ID
     * @param loginUser          登录用户
     * @return 处理后的流
     */
    public Flux<GenerationStreamEvent> handle(Flux<GenerationStreamEvent> originFlux,
                                              ChatHistoryService chatHistoryService,
                                              long appId, User loginUser) {
        // 收集数据用于生成后端记忆格式
        StringBuilder chatHistoryStringBuilder = new StringBuilder();
        // 用于跟踪已经见过的工具ID，判断是否是第一次调用
        Set<String> seenToolIds = new HashSet<>();
        Map<String, StringBuilder> toolArgumentBuffers = new java.util.HashMap<>();
        return originFlux
                .<GenerationStreamEvent>handle((event, sink) -> {
                    GenerationStreamEvent handledEvent = handleStreamEvent(event, chatHistoryStringBuilder, seenToolIds, toolArgumentBuffers);
                    if (handledEvent != null && (StrUtil.isNotBlank(handledEvent.getText()) || handledEvent.getData() != null)) {
                        sink.next(handledEvent);
                    }
                })
                .doOnComplete(() -> {
                    // 流式响应完成后，添加 AI 消息到对话历史
                    String aiResponse = chatHistoryStringBuilder.toString();
                    chatHistoryService.addChatMessage(appId, aiResponse, ChatHistoryMessageTypeEnum.AI.getValue(), loginUser.getId());
                })
                .doOnError(error -> {
                    // 如果AI回复失败，也要记录错误消息
                    GenerationErrorClassifier.GenerationError generationError =
                            GenerationErrorClassifier.classify(error);
                    String errorMessage = chatHistoryStringBuilder + "\n\nAI回复失败: " + generationError.message();
                    chatHistoryService.addChatMessage(appId, errorMessage, ChatHistoryMessageTypeEnum.AI.getValue(), loginUser.getId());
                });
    }

    /**
     * 解析并收集 TokenStream 数据
     */
    private GenerationStreamEvent handleStreamEvent(GenerationStreamEvent event,
                                                    StringBuilder chatHistoryStringBuilder,
                                                    Set<String> seenToolIds,
                                                    Map<String, StringBuilder> toolArgumentBuffers) {
        if (event == null || StrUtil.isBlank(event.getType())) {
            return null;
        }
        switch (event.getType()) {
            case GenerationStreamEvent.AI_DELTA -> {
                chatHistoryStringBuilder.append(event.getText());
                return event;
            }
            case GenerationStreamEvent.AI_THINKING_DELTA -> {
                return event;
            }
            case GenerationStreamEvent.TOOL_CALL -> {
                String toolId = event.getData() == null ? null : stringValue(event.getData().get("requestId"));
                String toolName = event.getData() == null ? null : stringValue(event.getData().get("toolName"));
                String argumentsChunk = event.getData() == null ? "" : StrUtil.blankToDefault(stringValue(event.getData().get("arguments")), "");
                String toolIndex = event.getData() == null ? null : stringValue(event.getData().get("toolIndex"));
                String arguments = appendToolArguments(toolArgumentBuffers, toolId, toolName, toolIndex, argumentsChunk);
                BaseTool tool = toolManager.getTool(toolName);
                boolean registered = tool != null;
                // 检查是否是第一次看到这个工具 ID
                if (toolId != null && !seenToolIds.contains(toolId)) {
                    // 第一次调用这个工具，记录 ID 并完整返回工具信息
                    seenToolIds.add(toolId);
                    if (tool == null) {
                        log.warn("收到未注册的工具请求: {}", toolName);
                        String output = String.format("\n\n[选择工具] %s（未注册工具）\n\n", toolName);
                        return GenerationStreamEvent.toolCall(output, buildToolEventData(
                                toolId,
                                toolName,
                                false,
                                arguments,
                                ""
                        ));
                    }
                    // 返回格式化的工具调用信息
                    String output = tool.generateToolRequestResponse();
                    return GenerationStreamEvent.toolCall(output, buildToolEventData(
                            toolId,
                            toolName,
                            true,
                            arguments,
                            ""
                    ));
                } else {
                    // 同一个工具调用的后续增量参数继续透传，供前端做更贴近 SSE 的文件预览
                    return GenerationStreamEvent.toolCall("", buildToolEventData(
                            toolId,
                            toolName,
                            registered,
                            arguments,
                            ""
                    ));
                }
            }
            case GenerationStreamEvent.TOOL_RESULT -> {
                String toolId = event.getData() == null ? null : stringValue(event.getData().get("requestId"));
                String toolName = event.getData() == null ? null : stringValue(event.getData().get("toolName"));
                String arguments = event.getData() == null ? "" : String.valueOf(event.getData().get("arguments"));
                clearToolArguments(toolArgumentBuffers, toolId, toolName, null);
                JSONObject jsonObject = StrUtil.isBlank(arguments) ? new JSONObject() : JSONUtil.parseObj(arguments);
                // 根据工具名称获取工具实例
                BaseTool tool = toolManager.getTool(toolName);
                if (tool == null) {
                    log.warn("收到未注册的工具执行结果: {}", toolName);
                    String fallbackResult = String.format("[工具调用] %s\n%s",
                            toolName,
                            StrUtil.blankToDefault(event.getText(), "(无结果)"));
                    String output = String.format("\n\n%s\n\n", fallbackResult);
                    chatHistoryStringBuilder.append(output);
                    return GenerationStreamEvent.toolResult(output, Map.of(
                            "requestId", StrUtil.blankToDefault(toolId, ""),
                            "toolName", toolName,
                            "registered", false,
                            "result", StrUtil.blankToDefault(event.getText(), "")
                    ));
                }
                String result = tool.generateToolExecutedResult(jsonObject, event.getText());
                // 输出前端和要持久化的内容
                String output = String.format("\n\n%s\n\n", result);
                chatHistoryStringBuilder.append(output);
                return GenerationStreamEvent.toolResult(output, buildToolEventData(
                        toolId,
                        toolName,
                        true,
                        arguments,
                        StrUtil.blankToDefault(event.getText(), "")
                ));
            }
            case GenerationStreamEvent.BUILD_RESULT -> {
                boolean success = event.getData() != null && Boolean.TRUE.equals(event.getData().get("success"));
                String resultText = success ? "成功" : "失败";
                String stage = event.getData() == null ? "unknown" : String.valueOf(event.getData().get("stage"));
                String summary = event.getData() == null ? "(无摘要)" : String.valueOf(event.getData().get("summary"));
                String output = String.format("""
                        
                        [构建结果] %s
                        阶段：%s
                        摘要：%s
                        """,
                        resultText,
                        stage,
                        summary);
                chatHistoryStringBuilder.append(output);
                event.setText(output);
                return event;
            }
            case GenerationStreamEvent.AGENT_EVENT, GenerationStreamEvent.GENERATION_STAGE -> {
                return event;
            }
            case GenerationStreamEvent.REPAIR_START, GenerationStreamEvent.GENERATION_ERROR -> {
                chatHistoryStringBuilder.append(event.getText());
                return event;
            }
            default -> {
                log.error("不支持的消息类型: {}", event.getType());
                return null;
            }
        }
    }

    private Map<String, Object> buildToolEventData(String requestId, String toolName, boolean registered, String arguments, String result) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("requestId", StrUtil.blankToDefault(requestId, ""));
        data.put("toolName", toolName);
        data.put("registered", registered);
        data.put("arguments", StrUtil.blankToDefault(arguments, ""));
        data.put("result", StrUtil.blankToDefault(result, ""));
        appendFileOperationData(data, arguments);
        return data;
    }

    private String appendToolArguments(Map<String, StringBuilder> toolArgumentBuffers,
                                       String toolId,
                                       String toolName,
                                       String toolIndex,
                                       String argumentsChunk) {
        String chunk = StrUtil.blankToDefault(argumentsChunk, "");
        String bufferKey = resolveToolBufferKey(toolId, toolName, toolIndex);
        if (StrUtil.isBlank(bufferKey)) {
            return chunk;
        }
        StringBuilder buffer = toolArgumentBuffers.computeIfAbsent(bufferKey, key -> new StringBuilder());
        buffer.append(chunk);
        return buffer.toString();
    }

    private void clearToolArguments(Map<String, StringBuilder> toolArgumentBuffers,
                                    String toolId,
                                    String toolName,
                                    String toolIndex) {
        String bufferKey = resolveToolBufferKey(toolId, toolName, toolIndex);
        if (StrUtil.isBlank(bufferKey)) {
            return;
        }
        toolArgumentBuffers.remove(bufferKey);
    }

    private String resolveToolBufferKey(String toolId, String toolName, String toolIndex) {
        if (StrUtil.isNotBlank(toolId)) {
            return toolId;
        }
        if (StrUtil.isNotBlank(toolName) && StrUtil.isNotBlank(toolIndex)) {
            return toolName + "#" + toolIndex;
        }
        return toolName;
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private void appendFileOperationData(Map<String, Object> data, String arguments) {
        if (StrUtil.isBlank(arguments)) {
            return;
        }
        boolean parsed = false;
        try {
            JSONObject jsonObject = JSONUtil.parseObj(arguments);
            String filePath = jsonObject.getStr("relativeFilePath");
            if (StrUtil.isNotBlank(filePath)) {
                data.put("filePath", filePath);
                parsed = true;
            }
            String content = jsonObject.getStr("content");
            if (content != null) {
                data.put("content", content);
                parsed = true;
            }
            String oldContent = jsonObject.getStr("oldContent");
            if (oldContent != null) {
                data.put("oldContent", oldContent);
                parsed = true;
            }
            String newContent = jsonObject.getStr("newContent");
            if (newContent != null) {
                data.put("newContent", newContent);
                parsed = true;
            }
        } catch (Exception e) {
            log.debug("解析文件工具参数失败: {}", e.getMessage());
        }
        if (!parsed) {
            appendPartialFileOperationData(data, arguments);
        }
    }

    private void appendPartialFileOperationData(Map<String, Object> data, String arguments) {
        putCompletedJsonStringValue(data, arguments, "relativeFilePath", "filePath");
        putPartialJsonStringValue(data, arguments, "content", "content");
        putPartialJsonStringValue(data, arguments, "oldContent", "oldContent");
        putPartialJsonStringValue(data, arguments, "newContent", "newContent");
    }

    private void putCompletedJsonStringValue(Map<String, Object> data, String arguments, String sourceKey, String targetKey) {
        String value = extractJsonStringValue(arguments, sourceKey, false);
        if (value != null) {
            data.put(targetKey, value);
        }
    }

    private void putPartialJsonStringValue(Map<String, Object> data, String arguments, String sourceKey, String targetKey) {
        String value = extractJsonStringValue(arguments, sourceKey, true);
        if (value != null) {
            data.put(targetKey, value);
        }
    }

    private String extractJsonStringValue(String jsonText, String key, boolean allowUnclosedValue) {
        String keyPattern = "\"" + key + "\"";
        int keyIndex = jsonText.indexOf(keyPattern);
        if (keyIndex < 0) {
            return null;
        }
        int colonIndex = jsonText.indexOf(':', keyIndex + keyPattern.length());
        if (colonIndex < 0) {
            return null;
        }
        int quoteIndex = jsonText.indexOf('"', colonIndex + 1);
        if (quoteIndex < 0) {
            return null;
        }
        StringBuilder valueBuilder = new StringBuilder();
        boolean escaping = false;
        for (int index = quoteIndex + 1; index < jsonText.length(); index++) {
            char currentChar = jsonText.charAt(index);
            if (escaping) {
                valueBuilder.append(decodeEscapedJsonChar(currentChar));
                escaping = false;
                continue;
            }
            if (currentChar == '\\') {
                escaping = true;
                continue;
            }
            if (currentChar == '"') {
                return valueBuilder.toString();
            }
            valueBuilder.append(currentChar);
        }
        return allowUnclosedValue ? valueBuilder.toString() : null;
    }

    private char decodeEscapedJsonChar(char escapedChar) {
        return switch (escapedChar) {
            case 'n' -> '\n';
            case 'r' -> '\r';
            case 't' -> '\t';
            case 'b' -> '\b';
            case 'f' -> '\f';
            default -> escapedChar;
        };
    }
}
