package com.rush.rushaicodemother.orchestration.edit;

import com.rush.rushaicodemother.orchestration.artifact.PatchApplyResult;
import com.rush.rushaicodemother.orchestration.patch.GenerationPatchApplyService;
import com.rush.rushaicodemother.orchestration.patch.PatchOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.List;

/**
 * 智能体编辑补丁服务实现。
 */
@Service
@RequiredArgsConstructor
public class AgentEditPatchService {

    private final GenerationPatchApplyService generationPatchApplyService;

    /**
 * 应用智能体编辑补丁。
 *
 * @param appId 应用编号
 * @param taskId 任务编号
 * @param projectRoot 项目根
 * @param changePlan {@code changePlan} 对应的调用参数
 * @param patchOperations 补丁操作
 * @return 智能体编辑补丁
 */
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
