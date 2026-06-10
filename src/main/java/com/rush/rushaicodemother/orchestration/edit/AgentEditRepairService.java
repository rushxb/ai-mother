package com.rush.rushaicodemother.orchestration.edit;

import cn.hutool.core.util.StrUtil;
import com.rush.rushaicodemother.ai.AiCodeEditService;
import com.rush.rushaicodemother.ai.AiCodeEditServiceFactory;
import com.rush.rushaicodemother.ai.model.EditResult;
import com.rush.rushaicodemother.orchestration.artifact.PatchApplyResult;
import com.rush.rushaicodemother.orchestration.patch.PatchOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class AgentEditRepairService {

    private final AiCodeEditServiceFactory aiCodeEditServiceFactory;
    private final AgentEditPlanningService planningService;

    public RepairAttempt repair(String userMessage,
                                String projectContext,
                                BackgroundValidationService.ValidationResult validationResult,
                                PatchApplyResult applyResult) {
        AiCodeEditService aiCodeEditService = aiCodeEditServiceFactory.createAiCodeEditService();
        String repairMessage = """
                %s

                上一次 AGENT_EDIT 补丁没有通过本地验证或补丁应用，请做一次最小范围局部修复，只返回 JSON 编辑操作。

                验证/应用失败信息:
                %s

                约束:
                1. 只修复失败日志直接相关的文件、import、变量、字段或 API 契约。
                2. 不要重写整个项目。
                3. 不要新增 package.json 未声明的第三方依赖 import。
                4. replace.oldContent 必须逐字来自项目上下文。
                """.formatted(
                StrUtil.blankToDefault(userMessage, ""),
                buildFailureMessage(validationResult, applyResult)
        );
        EditResult editResult = aiCodeEditService.editCode(repairMessage, projectContext);
        List<PatchOperation> operations = planningService.convertToPatchOperations(editResult);
        return new RepairAttempt(editResult, operations);
    }

    private String buildFailureMessage(BackgroundValidationService.ValidationResult validationResult,
                                       PatchApplyResult applyResult) {
        if (validationResult != null && StrUtil.isNotBlank(validationResult.message())) {
            return validationResult.message();
        }
        if (applyResult == null) {
            return "unknown_failure";
        }
        return StrUtil.blankToDefault(applyResult.reason(), applyResult.status())
                + " "
                + applyResult.rejectedOperations();
    }

    public record RepairAttempt(EditResult editResult, List<PatchOperation> patchOperations) {
    }
}
