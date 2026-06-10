package com.rush.rushaicodemother.orchestration.edit;

import com.rush.rushaicodemother.orchestration.artifact.PatchApplyResult;
import com.rush.rushaicodemother.orchestration.patch.GenerationPatchApplyService;
import com.rush.rushaicodemother.orchestration.patch.PatchOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.List;

@Service
@RequiredArgsConstructor
public class AgentEditPatchService {

    private final GenerationPatchApplyService generationPatchApplyService;

    public PatchApplyResult apply(Long appId,
                                  String taskId,
                                  Path projectRoot,
                                  EditChangePlan changePlan,
                                  List<PatchOperation> patchOperations) {
        return generationPatchApplyService.apply(
                appId,
                taskId,
                projectRoot,
                changePlan == null ? null : changePlan.toArtifactPlan(),
                patchOperations
        );
    }
}
