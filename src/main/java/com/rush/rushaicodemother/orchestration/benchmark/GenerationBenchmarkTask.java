package com.rush.rushaicodemother.orchestration.benchmark;

import com.rush.rushaicodemother.orchestration.intent.IntentOperationType;

import java.util.List;

/**
 * 生成基准测试任务的不可变数据载体。
 */
public record GenerationBenchmarkTask(
        String id,
        String mode,
        String codeGenType,
        String prompt,
        String expectedValidation,
        String scenario,
        GenerationBenchmarkDifficulty difficulty,
        List<String> capabilities,
        List<GenerationBenchmarkQualityDimension> requiredQualityDimensions,
        List<GenerationBenchmarkFixtureFile> fixtureFiles,
        List<GenerationBenchmarkSourceAssertion> sourceAssertions,
        String expectedRoute,
        List<String> forbiddenRoutes,
        IntentOperationType operation,
        GenerationBenchmarkFixtureKind fixtureKind,
        List<GenerationBenchmarkResponseAssertion> responseAssertions
) {

    public GenerationBenchmarkTask(String id,
                                   String mode,
                                   String codeGenType,
                                   String prompt,
                                   String expectedValidation) {
        this(
                id,
                mode,
                codeGenType,
                prompt,
                expectedValidation,
                "legacy",
                GenerationBenchmarkDifficulty.MEDIUM,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                mode,
                List.of(),
                inferOperation(mode),
                inferFixtureKind(mode),
                List.of()
        );
    }

    public GenerationBenchmarkTask {
        capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
        requiredQualityDimensions = requiredQualityDimensions == null
                ? List.of()
                : List.copyOf(requiredQualityDimensions);
        fixtureFiles = fixtureFiles == null ? List.of() : List.copyOf(fixtureFiles);
        sourceAssertions = sourceAssertions == null ? List.of() : List.copyOf(sourceAssertions);
        forbiddenRoutes = forbiddenRoutes == null ? List.of() : List.copyOf(forbiddenRoutes);
        responseAssertions = responseAssertions == null ? List.of() : List.copyOf(responseAssertions);
    }

    /** 保留尚未声明响应断言的数据集与测试构造入口。 */
    public GenerationBenchmarkTask(String id,
                                   String mode,
                                   String codeGenType,
                                   String prompt,
                                   String expectedValidation,
                                   String scenario,
                                   GenerationBenchmarkDifficulty difficulty,
                                   List<String> capabilities,
                                   List<GenerationBenchmarkQualityDimension> requiredQualityDimensions,
                                   List<GenerationBenchmarkFixtureFile> fixtureFiles,
                                   List<GenerationBenchmarkSourceAssertion> sourceAssertions,
                                   String expectedRoute,
                                   List<String> forbiddenRoutes,
                                   IntentOperationType operation,
                                   GenerationBenchmarkFixtureKind fixtureKind) {
        this(id, mode, codeGenType, prompt, expectedValidation, scenario, difficulty, capabilities,
                requiredQualityDimensions, fixtureFiles, sourceAssertions, expectedRoute, forbiddenRoutes,
                operation, fixtureKind, List.of());
    }

    /** 保留旧调用方构造合同；版本化数据集必须显式声明 operation 与 fixtureKind。 */
    public GenerationBenchmarkTask(String id,
                                   String mode,
                                   String codeGenType,
                                   String prompt,
                                   String expectedValidation,
                                   String scenario,
                                   GenerationBenchmarkDifficulty difficulty,
                                   List<String> capabilities,
                                   List<GenerationBenchmarkQualityDimension> requiredQualityDimensions,
                                   List<GenerationBenchmarkFixtureFile> fixtureFiles,
                                   List<GenerationBenchmarkSourceAssertion> sourceAssertions,
                                   String expectedRoute,
                                   List<String> forbiddenRoutes) {
        this(id, mode, codeGenType, prompt, expectedValidation, scenario, difficulty, capabilities,
                requiredQualityDimensions, fixtureFiles, sourceAssertions, expectedRoute, forbiddenRoutes,
                inferOperation(mode), inferFixtureKind(mode));
    }

    public GenerationBenchmarkTask(String id,
                                   String mode,
                                   String codeGenType,
                                   String prompt,
                                   String expectedValidation,
                                   String scenario,
                                   GenerationBenchmarkDifficulty difficulty,
                                   List<String> capabilities,
                                   List<GenerationBenchmarkQualityDimension> requiredQualityDimensions,
                                   List<GenerationBenchmarkFixtureFile> fixtureFiles,
                                   List<GenerationBenchmarkSourceAssertion> sourceAssertions) {
        this(id, mode, codeGenType, prompt, expectedValidation, scenario, difficulty, capabilities,
                requiredQualityDimensions, fixtureFiles, sourceAssertions, mode, List.of(),
                inferOperation(mode), inferFixtureKind(mode));
    }

    private static IntentOperationType inferOperation(String mode) {
        return "CREATE".equalsIgnoreCase(mode)
                ? IntentOperationType.CREATE
                : IntentOperationType.EDIT;
    }

    private static GenerationBenchmarkFixtureKind inferFixtureKind(String mode) {
        return "CREATE".equalsIgnoreCase(mode)
                ? GenerationBenchmarkFixtureKind.EMPTY_PROJECT
                : GenerationBenchmarkFixtureKind.TEMPLATE_PROJECT;
    }
}
