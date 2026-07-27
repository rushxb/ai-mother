package com.rush.rushaicodemother.orchestration;

import com.rush.rushaicodemother.orchestration.eventstream.GenerationEventStream;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContext;
import com.rush.rushaicodemother.memory.GenerationWorkingMemoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 用于会议及其活动运输的中央施工缝。 */
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
