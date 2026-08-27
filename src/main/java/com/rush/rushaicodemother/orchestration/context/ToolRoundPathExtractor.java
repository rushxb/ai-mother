package com.rush.rushaicodemother.orchestration.context;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rush.rushaicodemother.orchestration.tool.ToolResultEvidence;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

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

    /** mutation 结果的可信状态；UNKNOWN 与明确 no-op 必须保持可区分。 */
    public enum MutationEvidenceState {
        NOT_APPLICABLE,
        CONFIRMED_PATHS,
        CONFIRMED_NOOP,
        UNKNOWN
    }

    /** 单个文件路径字段的工具。 */
    private static final String SINGLE_PATH_FIELD = "relativeFilePath";

    /** 工具名 -> (作用类别, 提取器)。 */
    private static final Map<String, ExtractorSpec> EXTRACTORS = Map.of(
            "readFile", ExtractorSpec.read(
                    PathEffect.READ,
                    (node, result) -> canonicalRequestedPaths(singlePath(node))),
            "readMultipleFiles", ExtractorSpec.read(
                    PathEffect.READ, ToolRoundPathExtractor::successfulBatchReadPaths),
            "writeFile", ExtractorSpec.mutation(
                    PathEffect.MUTATE,
                    (node, result) -> effectiveMutationPaths(singlePath(node), result)),
            "modifyFile", ExtractorSpec.mutation(
                    PathEffect.MUTATE,
                    (node, result) -> effectiveMutationPaths(singlePath(node), result)),
            "writeFiles", ExtractorSpec.mutation(
                    PathEffect.MUTATE,
                    (node, result) -> effectiveMutationPaths(
                            arrayOfObjectPaths(node, "files"), result)),
            "deleteFile", ExtractorSpec.mutation(
                    PathEffect.DELETE,
                    (node, result) -> effectiveMutationPaths(singlePath(node), result)),
            "managePackageJson", ExtractorSpec.conditionalMutation(
                    PathEffect.MUTATE,
                    (node, result) -> effectiveMutationPaths(
                            List.of("package.json", "frontend/package.json"), result),
                    ToolRoundPathExtractor::packageActionMutatesWorkspace)
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
        if (request == null || request.name() == null) {
            return ExtractedPaths.empty();
        }
        ExtractorSpec spec = EXTRACTORS.get(request.name());
        if (spec == null) {
            return ExtractedPaths.empty();
        }
        if (result == null || !Objects.equals(request.name(), result.toolName())) {
            return unknownMutationOrEmpty(spec.effect());
        }
        if (Boolean.TRUE.equals(result.isError())) {
            return ExtractedPaths.notApplicable(spec.effect());
        }
        if (request.arguments() == null || request.arguments().isBlank()) {
            return unknownMutationOrEmpty(spec.effect());
        }
        try {
            JsonNode parsed = objectMapper.readTree(request.arguments());
            if (!parsed.isObject()) {
                return unknownMutationOrEmpty(spec.effect());
            }
            if (spec.effect() != PathEffect.READ
                    && !spec.mutationApplicable().test(parsed)) {
                return ExtractedPaths.notApplicable(spec.effect());
            }
            List<String> paths = spec.extractor().apply(parsed, result);
            return new ExtractedPaths(
                    spec.effect(),
                    paths,
                    mutationEvidenceState(spec.effect(), paths, result)
            );
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException malformed) {
            // mutation 参数损坏时保留 UNKNOWN，避免把可能已发生的副作用伪装成只读历史。
            return unknownMutationOrEmpty(spec.effect());
        }
    }

    private static MutationEvidenceState mutationEvidenceState(
            PathEffect effect,
            List<String> paths,
            ToolExecutionResultMessage result
    ) {
        if (effect == PathEffect.READ) {
            return MutationEvidenceState.NOT_APPLICABLE;
        }
        if (ToolResultEvidence.confirmsNoMutation(result)) {
            return MutationEvidenceState.CONFIRMED_NOOP;
        }
        return paths.isEmpty()
                ? MutationEvidenceState.UNKNOWN
                : MutationEvidenceState.CONFIRMED_PATHS;
    }

    private static ExtractedPaths unknownMutationOrEmpty(PathEffect effect) {
        return effect == PathEffect.READ
                ? ExtractedPaths.empty()
                : ExtractedPaths.unknown(effect);
    }

    /** package 查询与延迟安装不直接修改工作区，不应被误计为证据未知的 mutation。 */
    private static boolean packageActionMutatesWorkspace(JsonNode node) {
        JsonNode actionNode = node.get("action");
        if (actionNode == null || (actionNode.isTextual() && actionNode.textValue().isBlank())) {
            return false;
        }
        if (!actionNode.isTextual()) {
            return true;
        }
        return switch (actionNode.textValue().trim()) {
            case "getPackageJson", "installDependencies" -> false;
            default -> true;
        };
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
        return ToolResultEvidence.retainRequestedPaths(
                arrayOfStrings(node, "relativeFilePaths"),
                ToolResultEvidence.successfulReadPaths(result)
        );
    }

    private static List<String> effectiveMutationPaths(
            List<String> requestedPaths,
            ToolExecutionResultMessage result
    ) {
        return ToolResultEvidence.retainRequestedPaths(
                requestedPaths,
                ToolResultEvidence.effectiveMutationPaths(result)
        );
    }

    private static List<String> canonicalRequestedPaths(List<String> requestedPaths) {
        return ToolResultEvidence.retainRequestedPaths(requestedPaths, requestedPaths);
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
    public record ExtractedPaths(
            PathEffect effect,
            List<String> paths,
            MutationEvidenceState mutationEvidenceState
    ) {

        public ExtractedPaths {
            effect = Objects.requireNonNull(effect, "路径作用类别不能为空");
            paths = List.copyOf(Objects.requireNonNullElse(paths, List.of()));
            mutationEvidenceState = Objects.requireNonNull(
                    mutationEvidenceState, "mutation 证据状态不能为空");
            if (mutationEvidenceState == MutationEvidenceState.CONFIRMED_PATHS
                    && paths.isEmpty()) {
                throw new IllegalArgumentException("已确认 mutation 路径不能为空");
            }
            if ((mutationEvidenceState == MutationEvidenceState.CONFIRMED_NOOP
                    || mutationEvidenceState == MutationEvidenceState.UNKNOWN)
                    && !paths.isEmpty()) {
                throw new IllegalArgumentException("no-op 或未知 mutation 不能携带已确认路径");
            }
        }

        public static ExtractedPaths empty() {
            return notApplicable(PathEffect.READ);
        }

        public static ExtractedPaths notApplicable(PathEffect effect) {
            return new ExtractedPaths(
                    effect, List.of(), MutationEvidenceState.NOT_APPLICABLE);
        }

        public static ExtractedPaths unknown(PathEffect effect) {
            if (effect == PathEffect.READ) {
                return empty();
            }
            return new ExtractedPaths(
                    effect, List.of(), MutationEvidenceState.UNKNOWN);
        }
    }

    /** 一个工具的作用类别与参数提取函数。 */
    private record ExtractorSpec(
            PathEffect effect,
            java.util.function.BiFunction<JsonNode, ToolExecutionResultMessage, List<String>> extractor,
            Predicate<JsonNode> mutationApplicable
    ) {

        private static ExtractorSpec read(
                PathEffect effect,
                java.util.function.BiFunction<JsonNode, ToolExecutionResultMessage, List<String>> extractor
        ) {
            return new ExtractorSpec(effect, extractor, node -> false);
        }

        private static ExtractorSpec mutation(
                PathEffect effect,
                java.util.function.BiFunction<JsonNode, ToolExecutionResultMessage, List<String>> extractor
        ) {
            return conditionalMutation(effect, extractor, node -> true);
        }

        private static ExtractorSpec conditionalMutation(
                PathEffect effect,
                java.util.function.BiFunction<JsonNode, ToolExecutionResultMessage, List<String>> extractor,
                Predicate<JsonNode> mutationApplicable
        ) {
            return new ExtractorSpec(effect, extractor, mutationApplicable);
        }
    }
}
