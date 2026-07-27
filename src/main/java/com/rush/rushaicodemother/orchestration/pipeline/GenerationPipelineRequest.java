package com.rush.rushaicodemother.orchestration.pipeline;

import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.GenerationTaskRequest;
import com.rush.rushaicodemother.orchestration.router.GenerationMode;
import com.rush.rushaicodemother.orchestration.router.GenerationModeDecision;
import com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskExecution;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;

/**
 * 生成流水线请求参数。
 */
public record GenerationPipelineRequest(
        GenerationTaskRequest taskRequest,
        CodeGenTypeEnum codeGenType,
        GenerationWorkspace workspace,
        GenerationModeDecision modeDecision,
        GenerationTaskExecution execution
) {

    public GenerationPipelineRequest(GenerationTaskRequest taskRequest,
                                     CodeGenTypeEnum codeGenType,
                                     GenerationWorkspace workspace,
                                     GenerationModeDecision modeDecision) {
        this(taskRequest, codeGenType, workspace, modeDecision, null);
    }

    public boolean modeIs(GenerationMode mode) {
        return modeDecision != null && modeDecision.mode() == mode;
    }

    public GenerationTaskExecution requireExecution() {
        if (execution == null) {
            throw new IllegalStateException("generation task execution is not bound");
        }
        return execution;
    }

    public GenerationPipelineRequest withExecution(GenerationTaskExecution taskExecution) {
        return new GenerationPipelineRequest(taskRequest, codeGenType, workspace, modeDecision, taskExecution);
    }

    public GenerationPipelineRequest withModeDecision(GenerationModeDecision decision) {
        return new GenerationPipelineRequest(taskRequest, codeGenType, workspace, decision, execution);
    }
}
