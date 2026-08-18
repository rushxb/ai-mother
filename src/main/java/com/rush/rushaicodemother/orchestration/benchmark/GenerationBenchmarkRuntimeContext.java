package com.rush.rushaicodemother.orchestration.benchmark;

import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;

/** 托管基准评分者所需的运行时身份和工作区。 */
public record GenerationBenchmarkRuntimeContext(
        GenerationBenchmarkTask task,
        GenerationWorkspace workspace,
        long userId
) {

    public GenerationBenchmarkRuntimeContext {
        if (task == null
                || workspace == null
                || workspace.appId() == null
                || workspace.appId() <= 0
                || userId <= 0) {
            throw new IllegalArgumentException("benchmark runtime context requires task, workspace and user identity");
        }
    }
}
