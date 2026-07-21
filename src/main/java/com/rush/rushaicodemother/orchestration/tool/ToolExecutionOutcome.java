package com.rush.rushaicodemother.orchestration.tool;

/** Durable text result used to replay a completed tool invocation without repeating its side effect. */
public record ToolExecutionOutcome(boolean error, String resultText) {

    public ToolExecutionOutcome {
        resultText = resultText == null ? "" : resultText;
    }
}
