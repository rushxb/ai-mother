package com.rush.rushaicodemother.orchestration.pipeline;

import com.rush.rushaicodemother.model.enums.GenerationTaskStatus;

/** Result returned by a pipeline to the task runner. */
public record GenerationPipelineOutcome(
        GenerationPipelineDisposition disposition,
        String route,
        GenerationTaskStatus terminalStatus,
        String reason
) {

    public GenerationPipelineOutcome {
        if (disposition == null) {
            throw new IllegalArgumentException("pipeline disposition cannot be null");
        }
        if (route == null || route.isBlank()) {
            throw new IllegalArgumentException("pipeline route cannot be blank");
        }
        if (disposition == GenerationPipelineDisposition.COMPLETED
                && (terminalStatus == null || !terminalStatus.isTerminal())) {
            throw new IllegalArgumentException("completed pipeline must provide a terminal status");
        }
        if (disposition != GenerationPipelineDisposition.COMPLETED && terminalStatus != null) {
            throw new IllegalArgumentException("non-completed pipeline cannot provide a terminal status");
        }
    }

    public static GenerationPipelineOutcome completed(String route, GenerationTaskStatus status) {
        return new GenerationPipelineOutcome(GenerationPipelineDisposition.COMPLETED, route, status, null);
    }

    public static GenerationPipelineOutcome running(String route) {
        return new GenerationPipelineOutcome(GenerationPipelineDisposition.RUNNING, route, null, null);
    }

    public static GenerationPipelineOutcome fallback(String route, String reason) {
        return new GenerationPipelineOutcome(GenerationPipelineDisposition.FALLBACK, route, null, reason);
    }
}
