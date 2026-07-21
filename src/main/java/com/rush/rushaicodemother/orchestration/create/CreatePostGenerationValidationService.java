package com.rush.rushaicodemother.orchestration.create;

import com.rush.rushaicodemother.constant.AppConstant;
import com.rush.rushaicodemother.core.handler.GenerationStreamEvent;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.GenerationPreparation;
import com.rush.rushaicodemother.orchestration.GenerationSession;
import com.rush.rushaicodemother.orchestration.artifact.ChangePlan;
import com.rush.rushaicodemother.orchestration.artifact.GenerationArtifact;
import com.rush.rushaicodemother.orchestration.artifact.QualityGateResult;
import com.rush.rushaicodemother.orchestration.heavy.HeavyGenerationBuildValidationService;
import com.rush.rushaicodemother.orchestration.patch.PatchOperation;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionFence;
import com.rush.rushaicodemother.orchestration.template.SlotFillResult;
import com.rush.rushaicodemother.orchestration.tool.GenerationToolExecutionContextService;
import com.rush.rushaicodemother.orchestration.workspace.GenerationExecutionWorkspace;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class CreatePostGenerationValidationService {

    private final GenerationToolExecutionContextService generationToolExecutionContextService;
    private final HeavyGenerationBuildValidationService heavyGenerationBuildValidationService;

    public ValidationOutcome validate(Long appId,
                                      User loginUser,
                                      CodeGenTypeEnum codeGenType,
                                      String userMessage,
                                      String taskId,
                                      SlotFillResult result,
                                      GenerationSession session) {
        if (codeGenType != CodeGenTypeEnum.VUE_PROJECT && codeGenType != CodeGenTypeEnum.FULL_STACK_PROJECT) {
            return ValidationOutcome.skipped("backend_or_non_vue_create");
        }
        GenerationPreparation preparation = createRepairPreparation(codeGenType, userMessage, taskId, result);
        GenerationExecutionFence executionFence = executionFence(session);
        GenerationExecutionWorkspace executionWorkspace = session == null
                ? null
                : session.executionWorkspace();
        bindRepairContext(appId, codeGenType, taskId, result, executionFence, executionWorkspace);
        try {
            session.emit(GenerationStreamEvent.generationStage("CREATE 模板生成完成，正在执行构建验证...", Map.of(
                    "stage", AppConstant.GENERATING_STAGE_BUILD,
                    "taskId", taskId,
                    "route", "create"
            )));
            boolean passed = heavyGenerationBuildValidationService.runWithAutoRepair(
                    appId,
                    loginUser,
                    preparation,
                    session
            );
            return new ValidationOutcome(passed, true, passed ? "" : "create_post_generation_validation_failed");
        } finally {
            if (executionFence == null) {
                generationToolExecutionContextService.clearContext(appId, taskId);
            } else {
                generationToolExecutionContextService.clearContext(appId, taskId, executionFence);
            }
        }
    }

    private GenerationPreparation createRepairPreparation(CodeGenTypeEnum codeGenType,
                                                          String userMessage,
                                                          String taskId,
                                                          SlotFillResult result) {
        Map<String, GenerationArtifact> artifacts = new LinkedHashMap<>();
        artifacts.put("generation_spec", GenerationArtifact.of(
                "generation_spec",
                "CREATE",
                "CREATE 模板生成后验证规范",
                Map.of("requiresBuild", true)
        ));
        artifacts.put("change_plan", GenerationArtifact.of(
                "change_plan",
                "CREATE",
                "CREATE 模板生成后修复边界",
                changePlan(result).toPayload()
        ));
        return new GenerationPreparation(
                codeGenType,
                codeGenType,
                false,
                AppConstant.GENERATING_STAGE_CREATE,
                "【CREATE 构建修复任务】\n" + userMessage,
                List.of(GenerationStreamEvent.agentEvent("CREATE 模板生成后验证", Map.of(
                        "orchestrationMode", "create_post_generation_validation",
                        "route", "create",
                        "taskId", taskId
                ))),
                artifacts,
                QualityGateResult.passed(List.of(), List.of("create_template_generated")),
                Map.of(),
                taskId
        );
    }

    private ChangePlan changePlan(SlotFillResult result) {
        List<String> touchedFiles = result == null || result.patchOperations() == null
                ? List.of()
                : result.patchOperations().stream()
                .map(PatchOperation::relativePath)
                .distinct()
                .toList();
        return new ChangePlan(
                "v1",
                "create_build_repair",
                List.of(),
                touchedFiles,
                List.of(),
                List.of("create_template"),
                "build_validation",
                "manual_retry_without_snapshot"
        );
    }

    private void bindRepairContext(Long appId,
                                   CodeGenTypeEnum codeGenType,
                                   String taskId,
                                   SlotFillResult result,
                                   GenerationExecutionFence executionFence,
                                   GenerationExecutionWorkspace executionWorkspace) {
        generationToolExecutionContextService.bindChangePlan(
                appId,
                taskId,
                "create_build_repair",
                codeGenType,
                changePlan(result),
                true,
                "create_post_generation_build_repair",
                executionWorkspace == null ? null : executionWorkspace.workspace(),
                executionFence
        );
    }

    private GenerationExecutionFence executionFence(GenerationSession session) {
        return session == null || session.executionContext() == null
                ? null
                : session.executionContext().executionFence();
    }

    public record ValidationOutcome(boolean success, boolean executed, String reason) {

        private static ValidationOutcome skipped(String reason) {
            return new ValidationOutcome(true, false, reason);
        }
    }
}
