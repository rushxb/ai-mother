package com.rush.rushaicodemother.orchestration;

import com.rush.rushaicodemother.orchestration.eventstream.GenerationEventStream;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContext;
import com.rush.rushaicodemother.memory.GenerationWorkingMemoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Central construction seam for sessions and their event transport. */
@Component
@RequiredArgsConstructor
public class GenerationSessionFactory {

    private final GenerationEventStream generationEventStream;
    private final GenerationWorkingMemoryService workingMemoryService;

    public GenerationSession create(GenerationPreparation preparation,
                                    GenerationExecutionContext executionContext) {
        return new GenerationSession(
                preparation, executionContext, generationEventStream, workingMemoryService);
    }
}
