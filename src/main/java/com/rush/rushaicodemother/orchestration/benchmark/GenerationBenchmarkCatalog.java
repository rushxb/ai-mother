package com.rush.rushaicodemother.orchestration.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.intent.IntentOperationType;
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

    private static final String DATASET_RESOURCE = "benchmark/generation-benchmark-dataset-v3.json";
    private static final int SUPPORTED_SCHEMA_VERSION = 3;
    private static final int MINIMUM_TASK_COUNT = 55;
    private static final int MINIMUM_FIXTURES_PER_MATRIX_CELL = 3;
    private static final Pattern ID_PATTERN = Pattern.compile("[a-z0-9][a-z0-9_-]{0,63}");
    private static final Pattern VERSION_PATTERN = Pattern.compile("[0-9][A-Za-z0-9._-]{0,31}");
    private static final Set<String> MODES = Set.of(
            "CREATE", "READ_ONLY", "LIGHT_EDIT", "AGENT_EDIT", "HEAVY_EXPERT");
    private static final Set<String> VALIDATIONS = Set.of("fast", "build");
    private static final Set<String> SPECIALIZED_FUNCTIONAL_TASK_IDS = Set.of(
            "edit_copy",
            "edit_style",
            "edit_search_pagination",
            "edit_build_error",
            "edit_runtime_error",
            "edit_delete_module",
            "edit_heavy_architecture"
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

    /** 加载生成基准测试目录。 */
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
        // 先处理前置条件和快速返回分支，避免无效输入进入核心流程。
        if (task == null || task.id() == null || !ID_PATTERN.matcher(task.id()).matches()
                || !ids.add(task.id())) {
            throw new IllegalStateException("生成质量评测任务标识无效或重复");
        }
        if (!MODES.contains(task.mode())
                || task.expectedRoute() == null || !MODES.contains(task.expectedRoute())
                || CodeGenTypeEnum.getEnumByValue(task.codeGenType()) == null
                || task.prompt() == null || task.prompt().isBlank() || task.prompt().length() > 2_000
                || !VALIDATIONS.contains(task.expectedValidation())) {
            throw new IllegalStateException("生成质量评测任务基础字段无效: " + task.id());
        }
        if (task.forbiddenRoutes().stream().anyMatch(route -> route == null || !MODES.contains(route))
                || task.forbiddenRoutes().contains(task.expectedRoute())) {
            throw new IllegalStateException("生成质量评测路由约束无效: " + task.id());
        }
        if (task.scenario() == null || !ID_PATTERN.matcher(task.scenario()).matches()
                || task.difficulty() == null
                || task.operation() == null
                || task.fixtureKind() == null
                || task.capabilities().isEmpty() || task.capabilities().size() > 16
                || task.capabilities().stream().anyMatch(capability -> capability == null
                || !ID_PATTERN.matcher(capability).matches())
                || task.capabilities().stream().distinct().count() != task.capabilities().size()) {
            throw new IllegalStateException("生成质量评测任务元数据无效: " + task.id());
        }
        validateExecutionContract(task);
        CodeGenTypeEnum type = CodeGenTypeEnum.getEnumByValue(task.codeGenType());
        boolean readOnly = "READ_ONLY".equals(task.mode());
        boolean edit = task.operation() == IntentOperationType.EDIT
                || task.operation() == IntentOperationType.REPAIR;
        boolean runtimeCapable = !readOnly && (type == CodeGenTypeEnum.VUE_PROJECT
                || type == CodeGenTypeEnum.BACKEND_PROJECT
                || type == CodeGenTypeEnum.FULL_STACK_PROJECT);
        boolean visualCapable = !readOnly && (type == CodeGenTypeEnum.VUE_PROJECT
                || type == CodeGenTypeEnum.FULL_STACK_PROJECT);
        if (task.requiredQualityDimensions().isEmpty()
                || task.requiredQualityDimensions().stream().anyMatch(dimension -> dimension == null)
                || task.requiredQualityDimensions().stream().distinct().count()
                != task.requiredQualityDimensions().size()
                || !task.requiredQualityDimensions().contains(GenerationBenchmarkQualityDimension.SECURITY)
                || (!readOnly && !task.requiredQualityDimensions().contains(
                GenerationBenchmarkQualityDimension.STRUCTURAL))
                || (readOnly && task.requiredQualityDimensions().contains(
                GenerationBenchmarkQualityDimension.STRUCTURAL))
                || (readOnly && !task.requiredQualityDimensions().contains(
                GenerationBenchmarkQualityDimension.FUNCTIONAL))
                || (readOnly && !task.requiredQualityDimensions().contains(
                GenerationBenchmarkQualityDimension.DIFF_SCOPE))
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
        // 将可能失败的操作收敛在统一异常边界内，便于清理资源和转换错误。
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

    private void validateExecutionContract(GenerationBenchmarkTask task) {
        boolean readOnlyOperation = task.operation() == IntentOperationType.EXPLAIN
                || task.operation() == IntentOperationType.AUDIT
                || task.operation() == IntentOperationType.PLAN;
        if (readOnlyOperation != "READ_ONLY".equals(task.mode())
                || readOnlyOperation != "READ_ONLY".equals(task.expectedRoute())) {
            throw new IllegalStateException("生成质量评测只读操作与路由合同不一致: " + task.id());
        }
        if (task.operation() == IntentOperationType.CREATE
                && task.fixtureKind() != GenerationBenchmarkFixtureKind.EMPTY_PROJECT) {
            throw new IllegalStateException("首次创建评测必须使用空项目夹具: " + task.id());
        }
        if (task.operation() == IntentOperationType.PLAN
                && task.fixtureKind() != GenerationBenchmarkFixtureKind.EMPTY_PROJECT) {
            throw new IllegalStateException("空项目规划评测必须使用空项目夹具: " + task.id());
        }
        if ((task.operation() == IntentOperationType.EXPLAIN
                || task.operation() == IntentOperationType.AUDIT)
                && task.fixtureKind() != GenerationBenchmarkFixtureKind.TEMPLATE_PROJECT) {
            throw new IllegalStateException("仓库说明或审计评测必须使用模板项目夹具: " + task.id());
        }
        if ((task.operation() == IntentOperationType.EDIT
                || task.operation() == IntentOperationType.REPAIR)
                && task.fixtureKind() != GenerationBenchmarkFixtureKind.TEMPLATE_PROJECT) {
            throw new IllegalStateException("编辑或修复评测必须使用模板项目夹具: " + task.id());
        }
    }

    private void validateCoverage(List<GenerationBenchmarkTask> tasks) {
        Map<String, Long> modes = count(tasks, GenerationBenchmarkTask::mode);
        Map<String, Long> types = count(tasks, GenerationBenchmarkTask::codeGenType);
        Map<CapabilityMatrixCell, Long> matrixCells = tasks.stream().collect(Collectors.groupingBy(
                task -> new CapabilityMatrixCell(
                        task.expectedRoute(),
                        CodeGenTypeEnum.getEnumByValue(task.codeGenType())),
                Collectors.counting()
        ));
        Map<IntentOperationType, Long> operations = tasks.stream().collect(Collectors.groupingBy(
                GenerationBenchmarkTask::operation,
                () -> new EnumMap<>(IntentOperationType.class),
                Collectors.counting()
        ));
        Map<GenerationBenchmarkDifficulty, Long> difficulties = tasks.stream().collect(Collectors.groupingBy(
                GenerationBenchmarkTask::difficulty,
                () -> new EnumMap<>(GenerationBenchmarkDifficulty.class),
                Collectors.counting()
        ));
        long scenarioCount = tasks.stream().map(GenerationBenchmarkTask::scenario).distinct().count();
        long highRiskNegativeCount = tasks.stream()
                .filter(task -> !"AGENT_EDIT".equals(task.expectedRoute())
                        && !"HEAVY_EXPERT".equals(task.expectedRoute()))
                .filter(task -> task.forbiddenRoutes().contains("AGENT_EDIT")
                        || task.forbiddenRoutes().contains("HEAVY_EXPERT"))
                .count();
        // 数据集中的 route × 工程类型组合就是能力声明；新增组合必须一次提供足量夹具。
        boolean underrepresentedMatrixCell = matrixCells.values().stream()
                .anyMatch(count -> count < MINIMUM_FIXTURES_PER_MATRIX_CELL);
        if (underrepresentedMatrixCell
                || modes.getOrDefault("CREATE", 0L) < 10
                || modes.getOrDefault("READ_ONLY", 0L) < 9
                || modes.getOrDefault("LIGHT_EDIT", 0L) < 6
                || modes.getOrDefault("AGENT_EDIT", 0L) < 10
                || modes.getOrDefault("HEAVY_EXPERT", 0L) < 10
                || operations.getOrDefault(IntentOperationType.EXPLAIN, 0L) < 3
                || operations.getOrDefault(IntentOperationType.AUDIT, 0L) < 3
                || operations.getOrDefault(IntentOperationType.PLAN, 0L) < 3
                || types.getOrDefault("vue_project", 0L) < 12
                || types.getOrDefault("backend_project", 0L) < 5
                || types.getOrDefault("full_stack_project", 0L) < 5
                || types.getOrDefault("html", 0L) < 3
                || types.getOrDefault("multi_file", 0L) < 3
                || highRiskNegativeCount < 10
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

    private record CapabilityMatrixCell(String route, CodeGenTypeEnum codeGenType) {
    }
}
