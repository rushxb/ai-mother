package com.rush.rushaicodemother.orchestration.edit;

import com.rush.rushaicodemother.orchestration.artifact.ChangePlan;

import java.util.List;

/**
 * 编辑变更计划的不可变数据载体。
 */
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

    /**
 * 将当前对象转换为制品计划。
 *
 * @return 制品计划
 */
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
