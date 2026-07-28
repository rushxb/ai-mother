package com.rush.rushaicodemother.orchestration.patch;

import com.rush.rushaicodemother.infrastructure.diagnostic.LogExceptionSanitizer;
import com.rush.rushaicodemother.monitor.GenerationOrchestrationMetricsCollector;
import com.rush.rushaicodemother.orchestration.artifact.ChangePlan;
import com.rush.rushaicodemother.orchestration.artifact.GenerationArtifact;
import com.rush.rushaicodemother.orchestration.artifact.PatchApplyResult;
import com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskFenceGuard;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** 协调有界验证、事务性变更和补丁指标。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GenerationPatchApplyService {

    private final GenerationOrchestrationMetricsCollector metricsCollector;
    private final PatchOperationResourcePolicy resourcePolicy;
    private final PatchOperationValidator operationValidator;
    private final PatchOperationExecutor operationExecutor;
    private final GenerationTaskFenceGuard fenceGuard;

    /**
 * 应用生成补丁{@code Apply}。
 *
 * @param appId 应用编号
 * @param taskId 任务编号
 * @param projectRoot 项目根
 * @param changePlanArtifact {@code changePlanArtifact} 对应的调用参数
 * @param operations 操作
 * @return 生成补丁{@code Apply}
 */
    public PatchApplyResult apply(Long appId,
                                  String taskId,
                                  Path projectRoot,
                                  GenerationArtifact changePlanArtifact,
                                  List<PatchOperation> operations) {
        if (changePlanArtifact == null
                || changePlanArtifact.payload() == null
                || changePlanArtifact.payload().isEmpty()) {
            return record(PatchApplyResult.skipped(
                    appId, taskId, pathToString(normalizeRoot(projectRoot)), "change_plan_missing"));
        }
        return apply(
                appId,
                taskId,
                projectRoot,
                ChangePlan.fromPayload(payload(changePlanArtifact)),
                operations
        );
    }

    /**
 * 应用生成补丁{@code Apply}。
 *
 * @param appId 应用编号
 * @param taskId 任务编号
 * @param projectRoot 项目根
 * @param changePlan {@code changePlan} 对应的调用参数
 * @param operations 操作
 * @return 生成补丁{@code Apply}
 */
    public PatchApplyResult apply(Long appId,
                                  String taskId,
                                  Path projectRoot,
                                  ChangePlan changePlan,
                                  List<PatchOperation> operations) {
        Path normalizedRoot = normalizeRoot(projectRoot);
        String projectPath = pathToString(normalizedRoot);
        if (normalizedRoot == null || !Files.isDirectory(normalizedRoot)) {
            return record(PatchApplyResult.skipped(appId, taskId, projectPath, "project_root_missing"));
        }
        if (changePlan == null) {
            return record(PatchApplyResult.skipped(appId, taskId, projectPath, "change_plan_missing"));
        }
        if ("project_bootstrap".equals(changePlan.changeScope())) {
            return record(PatchApplyResult.skipped(
                    appId, taskId, projectPath, "project_bootstrap_not_patch_first"));
        }
        return validateAndExecute(appId, taskId, normalizedRoot, changePlan, operations, "planned_patch");
    }

    /**
 * 应用{@code Without}{@code Change}计划。
 *
 * @param appId 应用编号
 * @param taskId 任务编号
 * @param projectRoot 项目根
 * @param operations 操作
 * @param reason 原因
 * @return {@code Without}{@code Change}计划
 */
    public PatchApplyResult applyWithoutChangePlan(Long appId,
                                                   String taskId,
                                                   Path projectRoot,
                                                   List<PatchOperation> operations,
                                                   String reason) {
        Path normalizedRoot = normalizeRoot(projectRoot);
        String projectPath = pathToString(normalizedRoot);
        if (normalizedRoot == null || !Files.isDirectory(normalizedRoot)) {
            return record(PatchApplyResult.skipped(appId, taskId, projectPath, "project_root_missing"));
        }
        return validateAndExecute(appId, taskId, normalizedRoot, null, operations, reason);
    }

    /**
 * 渲染{@code Text}。
 *
 * @param result 待处理结果
 * @return 处理后的{@code Text}文本
 */
    public String renderText(PatchApplyResult result) {
        if (result == null) {
            return "补丁执行结果不可用";
        }
        if ("applied".equals(result.status())) {
            return "补丁执行成功，已落盘 " + result.appliedOperationCount() + " 个操作。";
        }
        if ("rejected".equals(result.status())) {
            return "补丁执行已拒绝，原因: " + result.reason()
                    + "，拒绝操作 " + result.rejectedOperationCount() + " 个。";
        }
        return "补丁执行已跳过: " + result.reason();
    }

    /** 校验{@code ate}{@code And}{@code Execute}是否有效。 */
    private PatchApplyResult validateAndExecute(Long appId,
                                                String taskId,
                                                Path projectRoot,
                                                ChangePlan changePlan,
                                                List<PatchOperation> operations,
                                                String executionContext) {
        String projectPath = projectRoot.toString();
        if (operations == null || operations.isEmpty()) {
            return record(PatchApplyResult.skipped(appId, taskId, projectPath, "patch_operations_empty"));
        }
        List<String> resourceBlockers = resourcePolicy.validate(operations);
        if (!resourceBlockers.isEmpty()) {
            return rejectedValidation(appId, taskId, projectPath, operations.size(), resourceBlockers);
        }
        PatchValidationResult validationResult = operationValidator.validate(projectRoot, changePlan, operations);
        if (!validationResult.rejectedOperations().isEmpty()) {
            return rejectedValidation(
                    appId,
                    taskId,
                    projectPath,
                    operations.size(),
                    validationResult.rejectedOperations()
            );
        }
        fenceGuard.assertCurrent(taskId);
        // 将可能失败的操作收敛在统一异常边界内，便于清理资源和转换错误。
        try {
            List<String> appliedFiles = operationExecutor.execute(validationResult.validOperations());
            return record(PatchApplyResult.applied(
                    appId, taskId, projectPath, operations.size(), appliedFiles));
        } catch (PatchWorkspaceException exception) {
            return rejectedValidation(
                    appId,
                    taskId,
                    projectPath,
                    operations.size(),
                    List.of("batch:" + exception.reason())
            );
        } catch (PatchBatchExecutionException exception) {
            log.warn("Patch batch execution failed, appId: {}, taskId: {}, context: {}",
                    appId, taskId, executionContext, LogExceptionSanitizer.sanitize(exception));
            return record(PatchApplyResult.rejected(
                    appId,
                    taskId,
                    projectPath,
                    operations.size(),
                    List.of("executor:patch_apply_failed"),
                    "patch_apply_failed"
            ));
        } catch (IOException exception) {
            log.warn("Patch rollback snapshot capture failed, appId: {}, taskId: {}, context: {}",
                    appId, taskId, executionContext, LogExceptionSanitizer.sanitize(exception));
            return rejectedValidation(
                    appId,
                    taskId,
                    projectPath,
                    operations.size(),
                    List.of("batch:rollback_snapshot_failed")
            );
        }
    }

    private PatchApplyResult rejectedValidation(Long appId,
                                                String taskId,
                                                String projectPath,
                                                int operationCount,
                                                List<String> blockers) {
        return record(PatchApplyResult.rejected(
                appId,
                taskId,
                projectPath,
                operationCount,
                blockers,
                "patch_operation_validation_failed"
        ));
    }

    private Path normalizeRoot(Path projectRoot) {
        return projectRoot == null ? null : projectRoot.toAbsolutePath().normalize();
    }

    private String pathToString(Path path) {
        return path == null ? "" : path.toString();
    }

    private Map<String, Object> payload(GenerationArtifact artifact) {
        return artifact == null || artifact.payload() == null ? Map.of() : artifact.payload();
    }

    private PatchApplyResult record(PatchApplyResult result) {
        if (metricsCollector != null) {
            metricsCollector.recordPatchApply(result.provider(), result.status(), result.reason());
        }
        return result;
    }
}
