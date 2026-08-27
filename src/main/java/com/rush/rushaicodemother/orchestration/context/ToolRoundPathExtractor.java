package com.rush.rushaicodemother.orchestration.context;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rush.rushaicodemother.orchestration.tool.ToolReadResultEvidence;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 从工具调用参数中提取受影响的文件路径。
 *
 * <p>按工具名注册提取策略：新增工具只需在 {@link #EXTRACTORS} 登记一条映射，
 * 无需修改折叠逻辑本身。未登记的工具视为不涉及具体路径，仅计入轮次统计。</p>
 */
@Component
public class ToolRoundPathExtractor {

    /** 工具对文件的作用类别，决定路径落入摘要的哪一栏。 */
    public enum PathEffect {
        READ, MUTATE, DELETE
    }

    /** 单个文件路径字段的工具。 */
    private static final String SINGLE_PATH_FIELD = "relativeFilePath";

    /** 工具名 -> (作用类别, 提取器)。 */
    private static final Map<String, ExtractorSpec> EXTRACTORS = Map.of(
            "readFile", new ExtractorSpec(PathEffect.READ, (node, result) -> singlePath(node)),
            "readMultipleFiles", new ExtractorSpec(
                    PathEffect.READ, ToolRoundPathExtractor::successfulBatchReadPaths),
            "writeFile", new ExtractorSpec(PathEffect.MUTATE, (node, result) -> singlePath(node)),
            "modifyFile", new ExtractorSpec(PathEffect.MUTATE, (node, result) -> singlePath(node)),
            "writeFiles", new ExtractorSpec(
                    PathEffect.MUTATE, (node, result) -> arrayOfObjectPaths(node, "files")),
            "deleteFile", new ExtractorSpec(PathEffect.DELETE, (node, result) -> singlePath(node))
    );

    private final ObjectMapper objectMapper;

    public ToolRoundPathExtractor(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "JSON 解析器不能为空");
    }

    /**
     * 提取一次工具调用涉及的路径。
     *
     * @param request 工具调用请求，允许为空
     * @param result 已完成的工具结果；明确失败的结果不能产生路径事实
     * @return 作用类别与路径列表；无法提取时返回空结果
     */
    public ExtractedPaths extract(ToolExecutionRequest request, ToolExecutionResultMessage result) {
        if (request == null
                || request.name() == null
                || result == null
                || Boolean.TRUE.equals(result.isError())) {
            return ExtractedPaths.empty();
        }
        ExtractorSpec spec = EXTRACTORS.get(request.name());
        if (spec == null || request.arguments() == null || request.arguments().isBlank()) {
            return ExtractedPaths.empty();
        }
        try {
            JsonNode parsed = objectMapper.readTree(request.arguments());
            if (!parsed.isObject()) {
                return ExtractedPaths.empty();
            }
            return new ExtractedPaths(spec.effect(), spec.extractor().apply(parsed, result));
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException malformed) {
            // 参数不可解析时降级为「无路径」，折叠摘要宁可少一条也不能中断生成。
            return ExtractedPaths.empty();
        }
    }

    private static List<String> singlePath(JsonNode node) {
        JsonNode value = node.get(SINGLE_PATH_FIELD);
        return value != null && value.isTextual() && !value.textValue().isBlank()
                ? List.of(value.textValue().trim())
                : List.of();
    }

    private static List<String> arrayOfStrings(JsonNode node, String field) {
        JsonNode array = node.get(field);
        if (array == null || !array.isArray()) {
            return List.of();
        }
        List<String> paths = new ArrayList<>();
        for (JsonNode element : array) {
            if (element.isTextual() && !element.textValue().isBlank()) {
                paths.add(element.textValue().trim());
            }
        }
        return List.copyOf(paths);
    }

    /**
     * 批量读取只接纳平台记录的成功项，并限制在原始请求集合内。
     *
     * <p>attributes 属于持久化边界，可能来自旧数据或损坏数据；与请求取交集后，
     * 即使证据被污染也不能向摘要注入本轮从未请求的路径。</p>
     */
    private static List<String> successfulBatchReadPaths(
            JsonNode node,
            ToolExecutionResultMessage result
    ) {
        Set<String> requestedPaths = new LinkedHashSet<>(
                arrayOfStrings(node, "relativeFilePaths"));
        if (requestedPaths.isEmpty()) {
            return List.of();
        }
        return ToolReadResultEvidence.successfulPaths(result).stream()
                .filter(requestedPaths::contains)
                .toList();
    }

    private static List<String> arrayOfObjectPaths(JsonNode node, String field) {
        JsonNode array = node.get(field);
        if (array == null || !array.isArray()) {
            return List.of();
        }
        List<String> paths = new ArrayList<>();
        for (JsonNode element : array) {
            if (!element.isObject()) {
                continue;
            }
            paths.addAll(singlePath(element));
        }
        return List.copyOf(paths);
    }

    /** 提取结果。 */
    public record ExtractedPaths(PathEffect effect, List<String> paths) {

        public ExtractedPaths {
            paths = List.copyOf(Objects.requireNonNullElse(paths, List.of()));
        }

        public static ExtractedPaths empty() {
            return new ExtractedPaths(PathEffect.READ, List.of());
        }
    }

    /** 一个工具的作用类别与参数提取函数。 */
    private record ExtractorSpec(
            PathEffect effect,
            java.util.function.BiFunction<JsonNode, ToolExecutionResultMessage, List<String>> extractor
    ) {
    }
}
