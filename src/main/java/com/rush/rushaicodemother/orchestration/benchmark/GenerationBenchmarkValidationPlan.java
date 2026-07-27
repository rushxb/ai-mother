package com.rush.rushaicodemother.orchestration.benchmark;

import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;

import java.nio.file.Path;
import java.util.List;

/** 内存中计划将一个夹具基线与其确定性分级器规则相结合。 */
public record GenerationBenchmarkValidationPlan(
        GenerationBenchmarkTask task,
        GenerationWorkspace workspace,
        GenerationBenchmarkWorkspaceSnapshot baseline,
        List<GenerationBenchmarkValidationRule> rules,
        List<GenerationBenchmarkRuntimeGrader> runtimeGraders,
        long userId
) {
    public GenerationBenchmarkValidationPlan {
        rules = rules == null ? List.of() : List.copyOf(rules);
        runtimeGraders = runtimeGraders == null ? List.of() : List.copyOf(runtimeGraders);
        userId = Math.max(0, userId);
    }

    public static GenerationBenchmarkValidationPlan empty() {
        Path root = Path.of(".").toAbsolutePath().normalize();
        return new GenerationBenchmarkValidationPlan(
                null,
                null,
                new GenerationBenchmarkWorkspaceSnapshot(root, java.util.Map.of()),
                List.of(),
                List.of(),
                0
        );
    }

    public GenerationBenchmarkValidationPlan withWorkspace(GenerationWorkspace publishedWorkspace) {
        if (publishedWorkspace == null) {
            throw new IllegalArgumentException("published benchmark workspace cannot be null");
        }
        return new GenerationBenchmarkValidationPlan(
                task,
                publishedWorkspace,
                baseline,
                rules,
                runtimeGraders,
                userId
        );
    }
}
