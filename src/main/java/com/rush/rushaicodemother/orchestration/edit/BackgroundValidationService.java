package com.rush.rushaicodemother.orchestration.edit;

import cn.hutool.core.util.StrUtil;
import com.rush.rushaicodemother.core.builder.VueProjectBuilder;
import com.rush.rushaicodemother.core.error.GenerationErrorClassifier;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.orchestration.GenerationTaskRequest;
import com.rush.rushaicodemother.orchestration.event.GenerationEventPublisher;
import com.rush.rushaicodemother.orchestration.event.GenerationEventType;
import com.rush.rushaicodemother.orchestration.patch.PatchOperation;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import com.rush.rushaicodemother.service.devserver.DevServerManager;
import com.rush.rushaicodemother.service.GenerationTraceService;
import com.rush.rushaicodemother.service.devserver.DevServerValidationResult;
import com.rush.rushaicodemother.service.devserver.DevServerValidationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 后台验证服务。
 * 负责在补丁应用成功后异步执行验证，不阻塞用户看到修改结果。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BackgroundValidationService {

    private final EditValidationPolicyService editValidationPolicyService;
    private final GenerationEventPublisher generationEventPublisher;
    private final GenerationTraceService generationTraceService;
    private final EditStatePersistenceService editStatePersistenceService;
    private final VueProjectBuilder vueProjectBuilder;
    private final DevServerManager devServerManager;
    private final DevServerValidationService devServerValidationService;

    /**
     * 异步执行后台验证。
     * 补丁应用成功后立即返回用户，后台再做验证。
     *
     * @param taskId           任务 ID
     * @param appId            应用 ID
     * @param userId           用户 ID
     * @param workspace        工作区
     * @param patchOperations  补丁操作列表
     * @param validationPlan   验证计划
     * @param userMessage      用户消息
     */
    @Async
    public void executeBackgroundValidation(
            String taskId,
            Long appId,
            Long userId,
            GenerationWorkspace workspace,
            List<PatchOperation> patchOperations,
            EditValidationPlan validationPlan,
            String userMessage) {
        executeValidation(taskId, appId, userId, workspace, patchOperations, validationPlan, userMessage);
    }

    public ValidationResult executeValidation(
            String taskId,
            Long appId,
            Long userId,
            GenerationWorkspace workspace,
            List<PatchOperation> patchOperations,
            EditValidationPlan validationPlan,
            String userMessage) {
        log.info("开始后台验证，taskId: {}, appId: {}, validationLevel: {}", taskId, appId, validationPlan.level());

        try {
            // 创建最小的请求对象用于事件发布
            GenerationTaskRequest request = createMinimalRequest(appId, userId, userMessage);

            // 发送验证开始事件
            generationEventPublisher.publish(request, GenerationEventType.VALIDATION_START, "后台验证开始", Map.of(
                    "taskId", taskId,
                    "validationLevel", validationPlan.level().name(),
                    "reason", validationPlan.reason()
            ));

            // 根据验证级别执行不同的验证
            ValidationResult result = switch (validationPlan.level()) {
                case NONE -> ValidationResult.skipped(taskId, "无需验证");
                case FAST_CHECK -> executeFastCheck(taskId, appId, workspace, patchOperations);
                case BUILD_REQUIRED -> executeBuildValidation(taskId, appId, userId, workspace, patchOperations, userMessage);
                case HEAVY_REVIEW_REQUIRED -> executeHeavyReview(taskId, appId, userId, workspace, patchOperations, userMessage);
            };

            // 保存验证结果到编辑状态
            editStatePersistenceService.recordValidationResult(appId, taskId, result);

            // 发送验证结果事件
            generationEventPublisher.publish(request, GenerationEventType.VALIDATION_RESULT, "后台验证完成", Map.of(
                    "taskId", taskId,
                    "status", result.status(),
                    "level", validationPlan.level().name(),
                    "message", result.message(),
                    "details", result.details()
            ));

            log.info("后台验证完成，taskId: {}, status: {}", taskId, result.status());
            return result;

        } catch (Exception e) {
            log.error("后台验证失败，taskId: {}", taskId, e);
            GenerationErrorClassifier.GenerationError publicError = GenerationErrorClassifier.classify(e);

            // 记录失败结果
            ValidationResult failedResult = ValidationResult.failed(taskId, "后台验证执行失败，请稍后重试");
            editStatePersistenceService.recordValidationResult(appId, taskId, failedResult);

            // 发送验证失败事件
            GenerationTaskRequest request = createMinimalRequest(appId, userId, userMessage);
            generationEventPublisher.publish(request, GenerationEventType.VALIDATION_RESULT, "后台验证失败", Map.of(
                    "taskId", taskId,
                    "status", "failed",
                    "category", publicError.category(),
                    "error", publicError.message()
            ));
            return failedResult;
        }
    }

    /**
     * 执行快速检查。
     */
    private ValidationResult executeFastCheck(String taskId, Long appId, GenerationWorkspace workspace, List<PatchOperation> patchOperations) {
        log.debug("执行快速检查，taskId: {}, 文件数: {}", taskId, patchOperations.size());

        // 检查文件是否存在且可读
        Path projectRoot = workspace.codeGenType() == com.rush.rushaicodemother.model.enums.CodeGenTypeEnum.FULL_STACK_PROJECT
                && workspace.frontendRootPath() != null
                ? workspace.frontendRootPath()
                : workspace.canonicalRootPath();
        List<String> invalidFiles = patchOperations.stream()
                .map(PatchOperation::relativePath)
                .filter(StrUtil::isNotBlank)
                .filter(relativePath -> {
                    Path filePath = projectRoot.resolve(relativePath);
                    return !java.nio.file.Files.exists(filePath) || !java.nio.file.Files.isReadable(filePath);
                })
                .toList();

        if (!invalidFiles.isEmpty()) {
            return ValidationResult.failed(taskId, "文件验证失败: " + String.join(", ", invalidFiles));
        }

        // 简单的语法检查（检查文件是否为空）
        List<String> emptyFiles = patchOperations.stream()
                .map(PatchOperation::relativePath)
                .filter(StrUtil::isNotBlank)
                .filter(relativePath -> {
                    Path filePath = projectRoot.resolve(relativePath);
                    try {
                        return java.nio.file.Files.exists(filePath) && java.nio.file.Files.size(filePath) == 0;
                    } catch (Exception e) {
                        return false;
                    }
                })
                .toList();

        if (!emptyFiles.isEmpty()) {
            return ValidationResult.warning(taskId, "以下文件为空: " + String.join(", ", emptyFiles));
        }

        return ValidationResult.success(taskId, "快速检查通过");
    }

    /**
     * 执行构建验证。
     * 在 Windows 上，dev server 会锁定 node_modules 中的文件（esbuild.exe、rollup 等），
     * 导致 pnpm install --force 失败（EPERM）。
     * 因此在构建前停止 dev server，构建后重启。
     */
    private ValidationResult executeBuildValidation(String taskId, Long appId, Long userId, GenerationWorkspace workspace, List<PatchOperation> patchOperations, String userMessage) {
        log.debug("执行构建验证，taskId: {}, 文件数: {}", taskId, patchOperations.size());

        Path projectRoot = workspace.codeGenType() == com.rush.rushaicodemother.model.enums.CodeGenTypeEnum.FULL_STACK_PROJECT
                && workspace.frontendRootPath() != null
                ? workspace.frontendRootPath()
                : workspace.canonicalRootPath();
        String projectPath = projectRoot.toString();
        log.info("构建验证，项目根目录: {}", projectPath);

        // 停止 dev server（如果正在运行），释放 node_modules 文件锁
        boolean devServerWasRunning = devServerManager.isRunning(appId);
        Integer savedPort = null;
        if (devServerWasRunning) {
            savedPort = devServerManager.getPort(appId);
            log.info("构建验证前停止 dev server，appId: {}, port: {}", appId, savedPort);
            devServerManager.stopDevServer(appId);
        }

        try {
            // 调用 VueProjectBuilder 执行实际构建
            VueProjectBuilder.BuildResult buildResult = vueProjectBuilder.buildProjectWithResult(projectPath);

            if (buildResult.success()) {
                if (editValidationPolicyService.isRuntimeErrorRepairRequest(userMessage)
                        && workspace.codeGenType() == com.rush.rushaicodemother.model.enums.CodeGenTypeEnum.VUE_PROJECT) {
                    DevServerValidationResult devServerValidationResult = devServerValidationService.validate(
                            taskId, appId, userId, workspace.codeGenType()
                    );
                    if (!devServerValidationResult.isPassed()) {
                        return ValidationResult.failed(taskId, "运行时验证失败: " + devServerValidationResult.summary());
                    }
                }
                log.info("构建验证通过，taskId: {}, stage: {}", taskId, buildResult.stage());
                return ValidationResult.success(taskId, "构建验证通过: " + buildResult.summary());
            } else {
                log.warn("构建验证失败，taskId: {}, stage: {}, summary: {}", taskId, buildResult.stage(), buildResult.summary());
                return ValidationResult.failed(taskId, "构建验证失败 [" + buildResult.stage() + "]: " + buildResult.summary());
            }
        } catch (Exception e) {
            log.error("构建验证异常，taskId: {}", taskId, e);
            return ValidationResult.failed(taskId, "构建验证执行异常，请稍后重试");
        } finally {
            // 构建完成后重启 dev server
            if (devServerWasRunning) {
                try {
                    log.info("构建验证后重启 dev server，appId: {}, port: {}", appId, savedPort);
                    App app = new App();
                    app.setId(appId);
                    if (workspace.codeGenType() != null) {
                        app.setCodeGenType(workspace.codeGenType().getValue());
                    }
                    if (savedPort != null) {
                        app.setDevServerPort(savedPort);
                    }
                    devServerManager.startDevServer(app, userId);
                } catch (Exception e) {
                    log.warn("重启 dev server 失败，appId: {}, 错误: {}", appId, e.getMessage());
                }
            }
        }
    }

    /**
     * 执行完整审查。
     */
    private ValidationResult executeHeavyReview(String taskId, Long appId, Long userId, GenerationWorkspace workspace, List<PatchOperation> patchOperations, String userMessage) {
        log.debug("执行完整审查，taskId: {}, 文件数: {}", taskId, patchOperations.size());

        // 先执行构建验证
        ValidationResult buildResult = executeBuildValidation(taskId, appId, userId, workspace, patchOperations, userMessage);
        if (!buildResult.isSuccess()) {
            return buildResult;
        }

        // 构建通过后，执行额外的文件级检查
        Path projectRoot = workspace.canonicalRootPath();
        List<String> invalidFiles = patchOperations.stream()
                .map(PatchOperation::relativePath)
                .filter(StrUtil::isNotBlank)
                .filter(relativePath -> {
                    Path filePath = projectRoot.resolve(relativePath);
                    return !java.nio.file.Files.exists(filePath) || !java.nio.file.Files.isReadable(filePath);
                })
                .toList();

        if (!invalidFiles.isEmpty()) {
            return ValidationResult.failed(taskId, "完整审查失败，以下文件不存在或不可读: " + String.join(", ", invalidFiles));
        }

        return ValidationResult.success(taskId, "完整审查通过（构建验证 + 文件完整性检查）");
    }

    /**
     * 创建最小的请求对象用于事件发布。
     */
    private GenerationTaskRequest createMinimalRequest(Long appId, Long userId, String userMessage) {
        App app = new App();
        app.setId(appId);
        User user = new User();
        user.setId(userId);
        return new GenerationTaskRequest(app, userMessage, user);
    }

    /**
     * 验证结果。
     */
    public record ValidationResult(
            String taskId,
            String status,
            String message,
            Map<String, Object> details
    ) {
        public static ValidationResult success(String taskId, String message) {
            return new ValidationResult(taskId, "success", message, Map.of());
        }

        public static ValidationResult warning(String taskId, String message) {
            return new ValidationResult(taskId, "warning", message, Map.of());
        }

        public static ValidationResult failed(String taskId, String message) {
            return new ValidationResult(taskId, "failed", message, Map.of());
        }

        public static ValidationResult skipped(String taskId, String message) {
            return new ValidationResult(taskId, "skipped", message, Map.of());
        }

        public boolean isSuccess() {
            return "success".equals(status);
        }
    }
}
