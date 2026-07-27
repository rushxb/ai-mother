package com.rush.rushaicodemother.orchestration.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 生成基准测试目录的后端领域类型。
 */
@Component
public class GenerationBenchmarkCatalog {

    private static final String DATASET_RESOURCE = "benchmark/generation-benchmark-dataset-v2.json";
    private static final int SUPPORTED_SCHEMA_VERSION = 2;
    private static final int MINIMUM_TASK_COUNT = 32;
    private static final Pattern ID_PATTERN = Pattern.compile("[a-z0-9][a-z0-9_-]{0,63}");
    private static final Pattern VERSION_PATTERN = Pattern.compile("[0-9][A-Za-z0-9._-]{0,31}");
    private static final Set<String> MODES = Set.of("CREATE", "LIGHT_EDIT", "AGENT_EDIT");
    private static final Set<String> VALIDATIONS = Set.of("fast", "build");
    private static final Set<String> SPECIALIZED_FUNCTIONAL_TASK_IDS = Set.of(
            "edit_copy",
            "edit_style",
            "edit_search_pagination",
            "edit_build_error",
            "edit_runtime_error",
            "edit_delete_module"
    );

    private final GenerationBenchmarkDataset dataset;

    public GenerationBenchmarkCatalog(ObjectMapper objectMapper) {
        this.dataset = load(objectMapper);
        validate(dataset);
    }

    public List<GenerationBenchmarkTask> tasks() {
        return dataset.tasks();
    }

    public GenerationBenchmarkDataset dataset() {
        return dataset;
    }

    private GenerationBenchmarkDataset load(ObjectMapper objectMapper) {
        if (objectMapper == null) {
            throw new IllegalArgumentException("评测数据集需要 JSON 解析器");
        }
        ClassPathResource resource = new ClassPathResource(DATASET_RESOURCE);
        try (InputStream input = resource.getInputStream()) {
            return objectMapper.readValue(input, GenerationBenchmarkDataset.class);
        } catch (Exception failure) {
            throw new IllegalStateException("生成质量评测数据集无法加载", failure);
        }
    }

    void validate(GenerationBenchmarkDataset candidate) {
        if (candidate == null || candidate.schemaVersion() != SUPPORTED_SCHEMA_VERSION
                || candidate.datasetId() == null || !ID_PATTERN.matcher(candidate.datasetId()).matches()
                || candidate.version() == null || !VERSION_PATTERN.matcher(candidate.version()).matches()) {
            throw new IllegalStateException("生成质量评测数据集元数据无效");
        }
        if (candidate.tasks().size() < MINIMUM_TASK_COUNT) {
            throw new IllegalStateException("生成质量评测任务数量不足");
        }
        Set<String> ids = new HashSet<>();
        for (GenerationBenchmarkTask task : candidate.tasks()) {
            validateTask(task, ids);
        }
        validateCoverage(candidate.tasks());
    }

    private void validateTask(GenerationBenchmarkTask task, Set<String> ids) {
        if (task == null || task.id() == null || !ID_PATTERN.matcher(task.id()).matches()
                || !ids.add(task.id())) {
            throw new IllegalStateException("生成质量评测任务标识无效或重复");
        }
        if (!MODES.contains(task.mode())
                || CodeGenTypeEnum.getEnumByValue(task.codeGenType()) == null
                || task.prompt() == null || task.prompt().isBlank() || task.prompt().length() > 2_000
                || !VALIDATIONS.contains(task.expectedValidation())) {
            throw new IllegalStateException("生成质量评测任务基础字段无效: " + task.id());
        }
        if (task.scenario() == null || !ID_PATTERN.matcher(task.scenario()).matches()
                || task.difficulty() == null
                || task.capabilities().isEmpty() || task.capabilities().size() > 16
                || task.capabilities().stream().anyMatch(capability -> capability == null
                || !ID_PATTERN.matcher(capability).matches())
                || task.capabilities().stream().distinct().count() != task.capabilities().size()) {
            throw new IllegalStateException("生成质量评测任务元数据无效: " + task.id());
        }
        CodeGenTypeEnum type = CodeGenTypeEnum.getEnumByValue(task.codeGenType());
        boolean edit = !"CREATE".equals(task.mode());
        boolean runtimeCapable = type == CodeGenTypeEnum.VUE_PROJECT
                || type == CodeGenTypeEnum.BACKEND_PROJECT
                || type == CodeGenTypeEnum.FULL_STACK_PROJECT;
        boolean visualCapable = type == CodeGenTypeEnum.VUE_PROJECT
                || type == CodeGenTypeEnum.FULL_STACK_PROJECT;
        if (task.requiredQualityDimensions().isEmpty()
                || task.requiredQualityDimensions().stream().anyMatch(dimension -> dimension == null)
                || task.requiredQualityDimensions().stream().distinct().count()
                != task.requiredQualityDimensions().size()
                || !task.requiredQualityDimensions().contains(GenerationBenchmarkQualityDimension.STRUCTURAL)
                || !task.requiredQualityDimensions().contains(GenerationBenchmarkQualityDimension.SECURITY)
                || (edit && !task.requiredQualityDimensions().contains(
                GenerationBenchmarkQualityDimension.FUNCTIONAL))
                || (edit && !task.requiredQualityDimensions().contains(
                GenerationBenchmarkQualityDimension.DIFF_SCOPE))
                || (runtimeCapable && !task.requiredQualityDimensions().contains(
                GenerationBenchmarkQualityDimension.RUNTIME))
                || (visualCapable && !task.requiredQualityDimensions().contains(
                GenerationBenchmarkQualityDimension.VISUAL))
                || (!runtimeCapable && task.requiredQualityDimensions().contains(
                GenerationBenchmarkQualityDimension.RUNTIME))
                || (!visualCapable && task.requiredQualityDimensions().contains(
                GenerationBenchmarkQualityDimension.VISUAL))) {
            throw new IllegalStateException("生成质量评测维度声明无效: " + task.id());
        }
        if (edit && task.sourceAssertions().isEmpty()
                && !SPECIALIZED_FUNCTIONAL_TASK_IDS.contains(task.id())) {
            throw new IllegalStateException("生成质量评测任务缺少功能评分器: " + task.id());
        }
        if (!task.sourceAssertions().isEmpty()
                && !task.requiredQualityDimensions().contains(
                GenerationBenchmarkQualityDimension.FUNCTIONAL)) {
            throw new IllegalStateException("声明式源码断言必须计入功能质量维度: " + task.id());
        }
        try {
            GenerationBenchmarkDeclarationValidator.validate(task);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalStateException("生成质量评测源码声明无效: " + task.id(), invalid);
        }
        if (task.fixtureFiles().stream().anyMatch(fixture -> !compatible(type, fixture.root()))
                || task.sourceAssertions().stream().anyMatch(assertion -> !compatible(type, assertion.root()))) {
            throw new IllegalStateException("生成质量评测源码根目录与工程类型不匹配: " + task.id());
        }
    }

    private void validateCoverage(List<GenerationBenchmarkTask> tasks) {
        Map<String, Long> modes = count(tasks, GenerationBenchmarkTask::mode);
        Map<String, Long> types = count(tasks, GenerationBenchmarkTask::codeGenType);
        Map<GenerationBenchmarkDifficulty, Long> difficulties = tasks.stream().collect(Collectors.groupingBy(
                GenerationBenchmarkTask::difficulty,
                () -> new EnumMap<>(GenerationBenchmarkDifficulty.class),
                Collectors.counting()
        ));
        long scenarioCount = tasks.stream().map(GenerationBenchmarkTask::scenario).distinct().count();
        if (modes.getOrDefault("CREATE", 0L) < 10
                || modes.getOrDefault("LIGHT_EDIT", 0L) < 6
                || modes.getOrDefault("AGENT_EDIT", 0L) < 10
                || types.getOrDefault("vue_project", 0L) < 12
                || types.getOrDefault("backend_project", 0L) < 5
                || types.getOrDefault("full_stack_project", 0L) < 5
                || difficulties.getOrDefault(GenerationBenchmarkDifficulty.EASY, 0L) < 6
                || difficulties.getOrDefault(GenerationBenchmarkDifficulty.MEDIUM, 0L) < 10
                || difficulties.getOrDefault(GenerationBenchmarkDifficulty.HARD, 0L) < 6
                || scenarioCount < 10) {
            throw new IllegalStateException("生成质量评测数据集覆盖配额不足");
        }
    }

    private Map<String, Long> count(List<GenerationBenchmarkTask> tasks,
                                    Function<GenerationBenchmarkTask, String> classifier) {
        return tasks.stream().collect(Collectors.groupingBy(classifier, Collectors.counting()));
    }

    private boolean compatible(CodeGenTypeEnum type, GenerationBenchmarkSourceRoot root) {
        if (root == null || root == GenerationBenchmarkSourceRoot.WORKSPACE) {
            return root != null;
        }
        if (type == CodeGenTypeEnum.VUE_PROJECT) {
            return root == GenerationBenchmarkSourceRoot.FRONTEND;
        }
        if (type == CodeGenTypeEnum.BACKEND_PROJECT) {
            return root == GenerationBenchmarkSourceRoot.BACKEND;
        }
        return type == CodeGenTypeEnum.FULL_STACK_PROJECT;
    }
}
