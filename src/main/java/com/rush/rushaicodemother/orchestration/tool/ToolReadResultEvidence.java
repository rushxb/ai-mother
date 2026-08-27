package com.rush.rushaicodemother.orchestration.tool;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.service.tool.ToolExecutionResult;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 只读工具结果中的平台可信证据。
 *
 * <p>展示文本会进入模型上下文，attributes 只进入持久记忆。本模块把二者绑定在
 * 同一个返回值中，并在 LangChain4j 工具结果转消息时传播版本化 attributes，避免
 * 从项目源码或展示文本反向猜测成功事实。缺失或损坏的历史 attributes 按无证据处理。</p>
 */
public final class ToolReadResultEvidence {

    private static final String SUCCESSFUL_PATHS_ATTRIBUTE = "rush.tool.read.successfulPaths.v1";
    private static final int MAX_PATHS = 10;

    private ToolReadResultEvidence() {
    }

    /** 创建只向模型暴露展示文本、同时携带平台成功路径证据的文本结果。 */
    public static TextContent successfulReads(String displayResult, List<String> successfulPaths) {
        String requiredDisplayResult = Objects.requireNonNull(displayResult, "工具展示结果不能为空");
        List<String> normalizedPaths = normalize(successfulPaths);
        if (normalizedPaths.isEmpty() || normalizedPaths.size() > MAX_PATHS) {
            throw new IllegalArgumentException("成功读取路径数量必须在 1 到 " + MAX_PATHS + " 之间");
        }
        return new EvidencedTextContent(requiredDisplayResult, normalizedPaths);
    }

    /** 把项目自有结果证据合并进 LangChain4j 原生 attributes。 */
    private static Map<String, Object> propagatedAttributes(ToolExecutionResult result) {
        Objects.requireNonNull(result, "工具执行结果不能为空");
        Map<String, Object> attributes = new LinkedHashMap<>(
                Objects.requireNonNullElse(result.attributes(), Map.of()));
        if (result.result() instanceof EvidencedTextContent evidencedText) {
            attributes.put(SUCCESSFUL_PATHS_ATTRIBUTE, evidencedText.successfulPaths());
        }
        return Collections.unmodifiableMap(attributes);
    }

    /**
     * 把真实工具执行结果转换为可持久化消息。
     *
     * <p>携证据的私有 {@link TextContent} 子类型只在当前进程内存在；进入消息、
     * Redis 和模型调用前会还原为官方标准类型，平台证据则独立写入 attributes。</p>
     */
    public static ToolExecutionResultMessage toMessage(
            ToolExecutionRequest request,
            ToolExecutionResult result
    ) {
        Objects.requireNonNull(request, "工具调用请求不能为空");
        Objects.requireNonNull(result, "工具执行结果不能为空");
        var builder = ToolExecutionResultMessage.builder()
                .id(request.id())
                .toolName(request.name())
                .isError(result.isError())
                .attributes(propagatedAttributes(result));
        if (result.result() instanceof EvidencedTextContent evidencedText) {
            builder.text(evidencedText.text());
        } else {
            builder.contents(result.resultContents());
        }
        return builder.build();
    }

    /** 从已持久化工具消息中恢复成功读取路径；旧格式或损坏格式返回空列表。 */
    public static List<String> successfulPaths(ToolExecutionResultMessage result) {
        if (result == null || result.attributes() == null) {
            return List.of();
        }
        Object value = result.attributes().get(SUCCESSFUL_PATHS_ATTRIBUTE);
        if (!(value instanceof List<?> paths) || paths.isEmpty() || paths.size() > MAX_PATHS) {
            return List.of();
        }
        LinkedHashSet<String> normalizedPaths = new LinkedHashSet<>();
        for (Object path : paths) {
            if (!(path instanceof String text) || text.isBlank()) {
                return List.of();
            }
            normalizedPaths.add(text.trim());
        }
        return List.copyOf(normalizedPaths);
    }

    private static List<String> normalize(List<String> paths) {
        if (paths == null) {
            return List.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String path : paths) {
            if (path == null || path.isBlank()) {
                throw new IllegalArgumentException("成功读取路径不能为空");
            }
            normalized.add(path.trim());
        }
        return List.copyOf(normalized);
    }

    /** 仅在进程内携带 attributes；序列化后的消息恢复为普通 {@link TextContent}。 */
    private static final class EvidencedTextContent extends TextContent {

        private final List<String> successfulPaths;

        private EvidencedTextContent(String text, List<String> successfulPaths) {
            super(text);
            this.successfulPaths = successfulPaths;
        }

        private List<String> successfulPaths() {
            return successfulPaths;
        }
    }
}
