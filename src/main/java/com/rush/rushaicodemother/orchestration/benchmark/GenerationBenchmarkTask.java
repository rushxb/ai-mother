package com.rush.rushaicodemother.orchestration.benchmark;

import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
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
        List<GenerationBenchmarkResponseAssertion> responseAssertions,
        String sourceCodeGenType,
        GenerationBenchmarkFallbackExpectation fallbackExpectation
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
                List.of(),
                codeGenType,
                GenerationBenchmarkFallbackExpectation.OPTIONAL
        );
    }

    /** 保留 v3.3 及既有 Java 调用方的构造合同；未声明来源类型时视为同类型任务。 */
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
                                   GenerationBenchmarkFixtureKind fixtureKind,
                                   List<GenerationBenchmarkResponseAssertion> responseAssertions) {
        this(id, mode, codeGenType, prompt, expectedValidation, scenario, difficulty, capabilities,
                requiredQualityDimensions, fixtureFiles, sourceAssertions, expectedRoute, forbiddenRoutes,
                operation, fixtureKind, responseAssertions, codeGenType);
    }

    /** 保留已显式声明来源类型的 v3.4 构造合同。 */
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
                                   GenerationBenchmarkFixtureKind fixtureKind,
                                   List<GenerationBenchmarkResponseAssertion> responseAssertions,
                                   String sourceCodeGenType) {
        this(id, mode, codeGenType, prompt, expectedValidation, scenario, difficulty, capabilities,
                requiredQualityDimensions, fixtureFiles, sourceAssertions, expectedRoute, forbiddenRoutes,
                operation, fixtureKind, responseAssertions, sourceCodeGenType,
                GenerationBenchmarkFallbackExpectation.OPTIONAL);
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
        sourceCodeGenType = sourceCodeGenType == null || sourceCodeGenType.isBlank()
                ? codeGenType
                : sourceCodeGenType.trim();
        fallbackExpectation = fallbackExpectation == null
                ? GenerationBenchmarkFallbackExpectation.OPTIONAL
                : fallbackExpectation;
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

    /** 返回夹具创建前的来源工程类型。 */
    public CodeGenTypeEnum sourceProjectType() {
        return CodeGenTypeEnum.getEnumByValue(sourceCodeGenType);
    }

    /** 返回数据集期望冻结并发布的目标工程类型。 */
    public CodeGenTypeEnum targetProjectType() {
        return CodeGenTypeEnum.getEnumByValue(codeGenType);
    }

    /** 判断当前任务是否要求保留既有能力并升级工程类型。 */
    public boolean crossTypeUpgrade() {
        CodeGenTypeEnum sourceType = sourceProjectType();
        CodeGenTypeEnum targetType = targetProjectType();
        return sourceType != null && targetType != null && sourceType != targetType;
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
