package com.rush.rushaicodemother.orchestration;

import com.rush.rushaicodemother.core.handler.GenerationStreamEvent;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import reactor.core.publisher.Flux;

public record GenerationTaskResult(
        String taskId,
        String route,
        GenerationWorkspace workspace,
        Flux<GenerationStreamEvent> contentFlux
) {
}
