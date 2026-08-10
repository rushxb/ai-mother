package com.rush.rushaicodemother.orchestration.context;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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
            "readFile", new ExtractorSpec(PathEffect.READ, ToolRoundPathExtractor::singlePath),
            "readMultipleFiles", new ExtractorSpec(
                    PathEffect.READ, node -> arrayOfStrings(node, "relativeFilePaths")),
            "writeFile", new ExtractorSpec(PathEffect.MUTATE, ToolRoundPathExtractor::singlePath),
            "modifyFile", new ExtractorSpec(PathEffect.MUTATE, ToolRoundPathExtractor::singlePath),
            "writeFiles", new ExtractorSpec(
                    PathEffect.MUTATE, node -> arrayOfObjectPaths(node, "files")),
            "deleteFile", new ExtractorSpec(PathEffect.DELETE, ToolRoundPathExtractor::singlePath)
    );

    private final ObjectMapper objectMapper;

    public ToolRoundPathExtractor(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "JSON 解析器不能为空");
    }

    /**
     * 提取一次工具调用涉及的路径。
     *
     * @param request 工具调用请求，允许为空
     * @return 作用类别与路径列表；无法提取时返回空结果
     */
    public ExtractedPaths extract(ToolExecutionRequest request) {
        if (request == null || request.name() == null) {
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
            return new ExtractedPaths(spec.effect(), spec.extractor().apply(parsed));
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
            java.util.function.Function<JsonNode, List<String>> extractor
    ) {
    }
}
