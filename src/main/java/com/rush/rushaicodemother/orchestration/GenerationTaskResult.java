package com.rush.rushaicodemother.orchestration;

import com.rush.rushaicodemother.core.handler.GenerationStreamEvent;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import reactor.core.publisher.Flux;

/**
 * 生成任务执行结果。
 */
public record GenerationTaskResult(
        String taskId,
        String route,
        GenerationWorkspace workspace,
        Flux<GenerationStreamEvent> contentFlux,
        boolean created
) {

    public GenerationTaskResult(String taskId,
                                String route,
                                GenerationWorkspace workspace,
                                Flux<GenerationStreamEvent> contentFlux) {
        this(taskId, route, workspace, contentFlux, true);
    }
}
