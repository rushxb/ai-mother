package com.rush.rushaicodemother.orchestration.pipeline;

import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.GenerationTaskRequest;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;

public record GenerationPipelineRequest(
        GenerationTaskRequest taskRequest,
        CodeGenTypeEnum codeGenType,
        GenerationWorkspace workspace
) {
}
