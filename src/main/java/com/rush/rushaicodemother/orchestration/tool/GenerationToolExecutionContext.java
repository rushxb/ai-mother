package com.rush.rushaicodemother.orchestration.tool;

import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.artifact.ChangePlan;

public record GenerationToolExecutionContext(
        Long appId,
        String taskId,
        String generationMode,
        CodeGenTypeEnum codeGenType,
        ChangePlan changePlan,
        boolean allowUnplannedWrite,
        String reason
) {

    public boolean allowsBootstrapWrite() {
        return allowUnplannedWrite
                || "full_generation".equals(generationMode)
                || "project_bootstrap".equals(changePlan == null ? null : changePlan.changeScope());
    }
}
