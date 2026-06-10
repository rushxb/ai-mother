package com.rush.rushaicodemother.orchestration.benchmark;

public record GenerationBenchmarkTask(
        String id,
        String mode,
        String codeGenType,
        String prompt,
        String expectedValidation
) {
}
