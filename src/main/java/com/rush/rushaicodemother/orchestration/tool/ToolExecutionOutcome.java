package com.rush.rushaicodemother.orchestration.tool;

/** 持久文本结果用于重播已完成的工具调用，而不会重复其副作用。 */
public record ToolExecutionOutcome(boolean error, String resultText) {

    public ToolExecutionOutcome {
        resultText = resultText == null ? "" : resultText;
    }
}
