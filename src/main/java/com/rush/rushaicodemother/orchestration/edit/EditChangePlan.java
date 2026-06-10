package com.rush.rushaicodemother.orchestration.edit;

import com.rush.rushaicodemother.orchestration.artifact.ChangePlan;

import java.util.List;

public record EditChangePlan(
        String scope,
        List<String> modifyFiles,
        List<String> addFiles,
        List<String> deleteFiles,
        List<String> steps,
        String validation,
        String riskLevel,
        String rollback
) {

    public ChangePlan toArtifactPlan() {
        return new ChangePlan(
                "v1",
                scope,
                addFiles,
                modifyFiles,
                deleteFiles,
                List.of(),
                validation,
                rollback
        );
    }
}
