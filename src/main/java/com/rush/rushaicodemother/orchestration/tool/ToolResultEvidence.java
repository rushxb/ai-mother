package com.rush.rushaicodemother.orchestration.tool;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.service.tool.ToolExecutionResult;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 工具展示文本与平台可信结果证据之间的持久化边界。
 *
 * <p>展示文本会进入模型上下文，attributes 只进入持久记忆。本模块把二者绑定在
 * 同一个返回值中，并在 LangChain4j 工具结果转消息时传播版本化 attributes，避免
 * 从请求参数或展示文本反向猜测成功事实。缺失、越界或损坏的历史证据一律按无事实处理。</p>
 */
public final class ToolResultEvidence {

    private static final String SUCCESSFUL_READ_PATHS_ATTRIBUTE =
            "rush.tool.read.successfulPaths.v1";
    private static final String EFFECTIVE_MUTATION_PATHS_ATTRIBUTE =
            "rush.tool.mutation.effectivePaths.v1";
    private static final String WORKSPACE_INVALIDATED_ATTRIBUTE =
            "rush.tool.workspace.invalidated.v1";
    private static final int MAX_READ_PATHS = 10;
    private static final int MAX_MUTATION_PATHS = 100;
    private static final int MAX_REQUEST_PATHS = 100;
    private static final Pattern WINDOWS_DRIVE_PATH =
            Pattern.compile("^[A-Za-z]:.*");

    private ToolResultEvidence() {
    }

    /** 创建只向模型暴露展示文本、同时携带平台成功读取路径的结果。 */
    public static TextContent successfulReads(String displayResult, List<String> successfulPaths) {
        String requiredDisplayResult = Objects.requireNonNull(displayResult, "工具展示结果不能为空");
        List<String> normalizedPaths = normalizeTrustedPaths(successfulPaths);
        if (normalizedPaths.isEmpty() || normalizedPaths.size() > MAX_READ_PATHS) {
            throw new IllegalArgumentException(
                    "成功读取路径数量必须在 1 到 " + MAX_READ_PATHS + " 之间");
        }
        return new EvidencedTextContent(
                requiredDisplayResult,
                Map.of(SUCCESSFUL_READ_PATHS_ATTRIBUTE, normalizedPaths)
        );
    }

    /** 创建携带实际工作区变更路径的结果；空路径表示成功但没有发生 mutation。 */
    public static TextContent effectiveMutations(
            String displayResult,
            List<String> effectiveMutationPaths
    ) {
        String requiredDisplayResult = Objects.requireNonNull(displayResult, "工具展示结果不能为空");
        List<String> normalizedPaths = normalizeTrustedPaths(effectiveMutationPaths);
        if (normalizedPaths.size() > MAX_MUTATION_PATHS) {
            throw new IllegalArgumentException(
                    "有效变更路径数量不能超过 " + MAX_MUTATION_PATHS);
        }
        return new EvidencedTextContent(
                requiredDisplayResult,
                Map.of(EFFECTIVE_MUTATION_PATHS_ATTRIBUTE, normalizedPaths)
        );
    }

    /**
     * 创建携带“此前工作区知识整体失效”事实的结果。
     *
     * <p>该事实用于快照回滚、工作区整体替换等 epoch 边界。它刻意不携带文件路径，
     * 因为下游必须废弃此前全部读取与 mutation 结论，而不能猜测一份不完整的路径集。</p>
     */
    public static TextContent workspaceInvalidated(String displayResult) {
        return new EvidencedTextContent(
                Objects.requireNonNull(displayResult, "工具展示结果不能为空"),
                Map.of(WORKSPACE_INVALIDATED_ATTRIBUTE, true)
        );
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

    /** 从已持久化工具消息恢复成功读取路径；旧格式或损坏格式返回空列表。 */
    public static List<String> successfulReadPaths(ToolExecutionResultMessage result) {
        return persistedPaths(result, SUCCESSFUL_READ_PATHS_ATTRIBUTE, MAX_READ_PATHS);
    }

    /** 从已持久化工具消息恢复实际工作区变更路径；旧格式或损坏格式返回空列表。 */
    public static List<String> effectiveMutationPaths(ToolExecutionResultMessage result) {
        return persistedPaths(result, EFFECTIVE_MUTATION_PATHS_ATTRIBUTE, MAX_MUTATION_PATHS);
    }

    /** 是否存在平台明确写入的“成功但无需变更”事实。 */
    public static boolean confirmsNoMutation(ToolExecutionResultMessage result) {
        return hasEffectiveMutationEvidence(result)
                && effectiveMutationPaths(result).isEmpty();
    }

    /** 是否携带格式有效的平台 mutation 证据，包括空列表代表的幂等 no-op。 */
    public static boolean hasEffectiveMutationEvidence(ToolExecutionResultMessage result) {
        if (result == null
                || Boolean.TRUE.equals(result.isError())
                || result.attributes() == null) {
            return false;
        }
        Object value = result.attributes().get(EFFECTIVE_MUTATION_PATHS_ATTRIBUTE);
        if (!(value instanceof List<?> paths) || paths.size() > MAX_MUTATION_PATHS) {
            return false;
        }
        return paths.isEmpty() || !effectiveMutationPaths(result).isEmpty();
    }

    /** 是否存在平台明确写入的工作区知识整体失效事实。 */
    public static boolean confirmsWorkspaceInvalidation(ToolExecutionResultMessage result) {
        return result != null
                && !Boolean.TRUE.equals(result.isError())
                && result.attributes() != null
                && Boolean.TRUE.equals(result.attributes().get(WORKSPACE_INVALIDATED_ATTRIBUTE));
    }

    /**
     * 只保留本次请求集合内的平台结果路径，并统一 Windows/Unix 相对路径写法。
     *
     * <p>请求参数和持久化 attributes 都属于边界输入；任一集合包含非法路径时整体
     * 失败关闭，不能让污染证据进入后续折叠摘要。</p>
     */
    public static List<String> retainRequestedPaths(
            List<String> requestedPaths,
            List<String> evidencedPaths
    ) {
        List<String> normalizedRequests = normalizeUntrustedPaths(
                requestedPaths, MAX_REQUEST_PATHS);
        List<String> normalizedEvidence = normalizeUntrustedPaths(
                evidencedPaths, MAX_MUTATION_PATHS);
        if (normalizedRequests.isEmpty() || normalizedEvidence.isEmpty()) {
            return List.of();
        }
        Set<String> requested = Set.copyOf(normalizedRequests);
        return normalizedEvidence.stream()
                .filter(requested::contains)
                .toList();
    }

    /** 把项目自有结果证据合并进 LangChain4j 原生 attributes。 */
    private static Map<String, Object> propagatedAttributes(ToolExecutionResult result) {
        Map<String, Object> attributes = new LinkedHashMap<>(
                Objects.requireNonNullElse(result.attributes(), Map.of()));
        // 保留键只能由本模块的私有 carrier 产生，普通 executor attributes 不具备事实权限。
        attributes.remove(SUCCESSFUL_READ_PATHS_ATTRIBUTE);
        attributes.remove(EFFECTIVE_MUTATION_PATHS_ATTRIBUTE);
        attributes.remove(WORKSPACE_INVALIDATED_ATTRIBUTE);
        // 失败结果必须在物理消息层就移除成功事实，避免持久记录出现互相矛盾的状态。
        if (!Boolean.TRUE.equals(result.isError())
                && result.result() instanceof EvidencedTextContent evidencedText) {
            attributes.putAll(evidencedText.trustedEvidence());
        }
        return Collections.unmodifiableMap(attributes);
    }

    private static List<String> persistedPaths(
            ToolExecutionResultMessage result,
            String attribute,
            int maxPaths
    ) {
        if (result == null
                || Boolean.TRUE.equals(result.isError())
                || result.attributes() == null) {
            return List.of();
        }
        Object value = result.attributes().get(attribute);
        if (!(value instanceof List<?> paths) || paths.size() > maxPaths) {
            return List.of();
        }
        List<String> stringPaths = paths.stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .toList();
        if (stringPaths.size() != paths.size()) {
            return List.of();
        }
        return normalizeUntrustedPaths(stringPaths, maxPaths);
    }

    private static List<String> normalizeTrustedPaths(List<String> paths) {
        Objects.requireNonNull(paths,
                "工具结果路径列表不能为空；空列表才表示明确的成功 no-op");
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String path : paths) {
            String normalizedPath = normalizeRelativePath(path);
            if (normalizedPath == null) {
                throw new IllegalArgumentException("工具结果路径必须是合法的项目内相对路径");
            }
            normalized.add(normalizedPath);
        }
        return List.copyOf(normalized);
    }

    private static List<String> normalizeUntrustedPaths(List<String> paths, int maxPaths) {
        if (paths == null || paths.size() > maxPaths) {
            return List.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String path : paths) {
            String normalizedPath = normalizeRelativePath(path);
            if (normalizedPath == null) {
                return List.of();
            }
            normalized.add(normalizedPath);
        }
        return List.copyOf(normalized);
    }

    private static String normalizeRelativePath(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        String portablePath = path.trim().replace('\\', '/');
        if (portablePath.startsWith("/") || WINDOWS_DRIVE_PATH.matcher(portablePath).matches()) {
            return null;
        }
        try {
            Path parsedPath = Path.of(portablePath);
            if (parsedPath.isAbsolute()) {
                return null;
            }
            for (Path segment : parsedPath) {
                if ("..".equals(segment.toString())) {
                    return null;
                }
            }
            String normalized = parsedPath.normalize().toString().replace('\\', '/');
            return normalized.isBlank() || ".".equals(normalized) ? null : normalized;
        } catch (InvalidPathException invalidPath) {
            return null;
        }
    }

    /** 仅在进程内携带 attributes；序列化后的消息恢复为普通 {@link TextContent}。 */
    private static final class EvidencedTextContent extends TextContent {

        private final Map<String, Object> trustedEvidence;

        private EvidencedTextContent(String text, Map<String, ?> trustedEvidence) {
            super(text);
            this.trustedEvidence = Map.copyOf(trustedEvidence);
        }

        private Map<String, Object> trustedEvidence() {
            return trustedEvidence;
        }
    }
}
