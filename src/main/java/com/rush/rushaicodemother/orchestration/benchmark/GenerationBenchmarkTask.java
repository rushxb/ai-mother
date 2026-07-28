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
        List<GenerationBenchmarkSourceAssertion> sourceAssertions
) {

    /**
 * 创建生成基准测试任务实例并完成必要的依赖和初始状态设置。
 *
 * @param id 编号
 * @param mode 模式
 * @param codeGenType 代码生成类型
 * @param prompt 提示词
 * @param expectedValidation {@code expectedValidation} 对应的调用参数
 */
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
    }
}
