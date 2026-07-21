package com.rush.rushaicodemother.orchestration.tool;

import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.artifact.ChangePlan;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionFence;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;

public record GenerationToolExecutionContext(
        Long appId,
        String taskId,
        String generationMode,
        CodeGenTypeEnum codeGenType,
        ChangePlan changePlan,
        boolean allowUnplannedWrite,
        String reason,
        GenerationWorkspace workspace,
        GenerationExecutionFence executionFence
) {

    public GenerationToolExecutionContext(Long appId,
                                          String taskId,
                                          String generationMode,
                                          CodeGenTypeEnum codeGenType,
                                          ChangePlan changePlan,
                                          boolean allowUnplannedWrite,
                                          String reason) {
        this(appId, taskId, generationMode, codeGenType, changePlan,
                allowUnplannedWrite, reason, null, null);
    }

    public GenerationToolExecutionContext(Long appId,
                                          String taskId,
                                          String generationMode,
                                          CodeGenTypeEnum codeGenType,
                                          ChangePlan changePlan,
                                          boolean allowUnplannedWrite,
                                          String reason,
                                          GenerationWorkspace workspace) {
        this(appId, taskId, generationMode, codeGenType, changePlan,
                allowUnplannedWrite, reason, workspace, null);
    }

    public boolean allowsBootstrapWrite() {
        return allowUnplannedWrite
                || "full_generation".equals(generationMode)
                || "project_bootstrap".equals(changePlan == null ? null : changePlan.changeScope());
    }
}
