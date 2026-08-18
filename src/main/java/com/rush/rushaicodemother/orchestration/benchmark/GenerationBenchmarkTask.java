package com.rush.rushaicodemother.orchestration.benchmark;

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
        List<String> forbiddenRoutes
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
        expectedRoute = expectedRoute == null || expectedRoute.isBlank() ? mode : expectedRoute;
        forbiddenRoutes = forbiddenRoutes == null ? List.of() : List.copyOf(forbiddenRoutes);
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
                requiredQualityDimensions, fixtureFiles, sourceAssertions, mode, List.of());
    }
}
