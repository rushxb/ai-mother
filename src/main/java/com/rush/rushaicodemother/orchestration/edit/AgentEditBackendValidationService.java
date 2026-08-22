package com.rush.rushaicodemother.orchestration.edit;

import cn.hutool.core.util.StrUtil;
import com.rush.rushaicodemother.config.PatchExecutionProperties;
import com.rush.rushaicodemother.core.builder.GoBuildResult;
import com.rush.rushaicodemother.core.builder.GoProjectBuilder;
import com.rush.rushaicodemother.infrastructure.diagnostic.LogExceptionSanitizer;
import com.rush.rushaicodemother.orchestration.patch.PatchOperation;
import com.rush.rushaicodemother.orchestration.patch.PatchWorkspaceException;
import com.rush.rushaicodemother.orchestration.patch.PatchWorkspaceFileService;
import com.rush.rushaicodemother.orchestration.patch.PatchWorkspaceTarget;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionPolicyException;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import com.rush.rushaicodemother.security.workspace.GeneratedSqlSafetyPolicy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 应用编辑补丁后对后端文件执行有界的、轻量级的验证。
 *
 * <p>所有文件解析和读取都经过{@link PatchWorkspaceFileService}；这项服务从来没有
 * 直接解析或读取不受信任的补丁路径。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentEditBackendValidationService {

    private final PatchWorkspaceFileService workspaceFileService;
    private final PatchExecutionProperties patchExecutionProperties;
    private final GoProjectBuilder goProjectBuilder;
    private final GeneratedSqlSafetyPolicy sqlSafetyPolicy;

    /**
 * 校验{@code ate}是否有效。
 *
 * @param taskId 任务编号
 * @param workspace 工作区
 * @param patchOperations 补丁操作
 * @return {@code ate}
 */
    public BackgroundValidationService.ValidationResult validate(String taskId,
                                                                 GenerationWorkspace workspace,
                                                                 List<PatchOperation> patchOperations) {
        return validateInternal(taskId, workspace, patchOperations, false);
    }

    /** 按编辑验证计划执行后端静态检查，并在需要时追加 Go 构建门禁。 */
    public BackgroundValidationService.ValidationResult validate(String taskId,
                                                                 GenerationWorkspace workspace,
                                                                 List<PatchOperation> patchOperations,
                                                                 EditValidationPlan validationPlan) {
        Objects.requireNonNull(validationPlan, "编辑验证计划不能为空");
        return validateInternal(taskId, workspace, patchOperations, validationPlan.requiresBuild());
    }

    private BackgroundValidationService.ValidationResult validateInternal(
            String taskId,
            GenerationWorkspace workspace,
            List<PatchOperation> patchOperations,
            boolean buildRequired) {
        // 先处理前置条件和快速返回分支，避免无效输入进入核心流程。
        if (workspace == null || patchOperations == null || patchOperations.isEmpty()) {
            return BackgroundValidationService.ValidationResult.skipped(taskId, "无后端补丁需要验证");
        }
        if (patchOperations.size() > patchExecutionProperties.getMaxOperations()) {
            return BackgroundValidationService.ValidationResult.failed(taskId, "后端补丁数量超过校验上限");
        }
        Path backendRoot = resolveBackendRoot(workspace);
        if (backendRoot == null) {
            return BackgroundValidationService.ValidationResult.failed(taskId, "后端工作区不可用");
        }

        List<String> errors = new ArrayList<>();
        // 按既定顺序逐项处理，并在达到资源或状态边界时提前结束。
        for (PatchOperation operation : patchOperations) {
            if (operation == null || PatchOperation.ACTION_DELETE.equals(operation.action())) {
                continue;
            }
            String relativePath = normalizePath(operation.relativePath());
            if (StrUtil.isBlank(relativePath) || !isBackendFile(relativePath)) {
                continue;
            }
            String backendRelativePath = stripBackendPrefix(relativePath);
            String displayPath = safeDiagnosticValue(relativePath, "未知文件");
            try {
                PatchWorkspaceTarget target = workspaceFileService.resolve(backendRoot, backendRelativePath);
                if (!workspaceFileService.isRegularFile(target)) {
                    errors.add(displayPath + ":文件不存在");
                    continue;
                }
                String content = workspaceFileService.readUtf8(target);
                if (relativePath.endsWith(".go")) {
                    validateGo(displayPath, content, errors);
                } else if (relativePath.endsWith(".sql")) {
                    validateSql(displayPath, content, errors);
                }
            } catch (PatchWorkspaceException exception) {
                log.warn(
                        "后端补丁文件访问被拒绝，taskId: {}, relativePath: {}, reason: {}",
                        safeDiagnosticValue(taskId, "unknown"),
                        displayPath,
                        safeDiagnosticValue(exception.reason(), "workspace_policy_rejected")
                );
                errors.add(displayPath + ":读取失败");
            } catch (Exception exception) {
                log.warn(
                        "读取后端补丁文件失败，taskId: {}, relativePath: {}",
                        safeDiagnosticValue(taskId, "unknown"),
                        displayPath,
                        LogExceptionSanitizer.sanitize(exception)
                );
                errors.add(displayPath + ":读取失败");
            }
        }
        if (!errors.isEmpty()) {
            return BackgroundValidationService.ValidationResult.failed(taskId, "后端轻量验证失败: " + String.join("; ", errors));
        }
        if (!buildRequired) {
            return BackgroundValidationService.ValidationResult.success(taskId, "后端轻量验证通过");
        }
        return executeBuildValidation(taskId, backendRoot);
    }

    /** 静态检查通过后执行真实 Go 构建，确保 BUILD 计划不会退化为语法检查。 */
    private BackgroundValidationService.ValidationResult executeBuildValidation(String taskId, Path backendRoot) {
        try {
            GoBuildResult buildResult = goProjectBuilder.buildProjectWithResult(backendRoot.toString(), taskId);
            if (buildResult.success()) {
                return BackgroundValidationService.ValidationResult.success(
                        taskId,
                        "后端构建验证通过: " + buildResult.publicSummary()
                );
            }
            return BackgroundValidationService.ValidationResult.failed(
                    taskId,
                    "后端构建验证失败 [" + buildResult.stage() + "]: " + buildResult.publicSummary()
            );
        } catch (GenerationExecutionPolicyException exception) {
            throw exception;
        } catch (Exception exception) {
            log.error("后端构建验证执行异常，taskId: {}",
                    safeDiagnosticValue(taskId, "unknown"),
                    LogExceptionSanitizer.sanitize(exception));
            return BackgroundValidationService.ValidationResult.failed(
                    taskId,
                    "后端构建验证执行异常，请稍后重试"
            );
        }
    }

    /** 校验{@code ate}{@code Go}是否有效。 */
    private void validateGo(String relativePath, String content, List<String> errors) {
        String normalizedContent = StrUtil.blankToDefault(content, "");
        String trimmed = normalizedContent.stripLeading();
        if (!trimmed.startsWith("package ")) {
            errors.add(relativePath + ":缺少 package 声明");
        }
        if (!balanced(normalizedContent, '{', '}')) {
            errors.add(relativePath + ":花括号不平衡");
        }
        if (!balanced(normalizedContent, '(', ')')) {
            errors.add(relativePath + ":圆括号不平衡");
        }
    }

    private void validateSql(String relativePath, String content, List<String> errors) {
        if (!sqlSafetyPolicy.validateAll(content).isEmpty()) {
            errors.add(relativePath + ":包含危险 SQL");
        }
    }

    /** 返回{@code balanced}。 */
    private boolean balanced(String content, char open, char close) {
        int depth = 0;
        for (int index = 0; index < content.length(); index++) {
            char current = content.charAt(index);
            if (current == open) {
                depth++;
            } else if (current == close) {
                depth--;
                if (depth < 0) {
                    return false;
                }
            }
        }
        return depth == 0;
    }

    private Path resolveBackendRoot(GenerationWorkspace workspace) {
        Path root = workspace.backendRootPath() == null ? workspace.canonicalRootPath() : workspace.backendRootPath();
        return root == null ? null : root.toAbsolutePath().normalize();
    }

    private String stripBackendPrefix(String relativePath) {
        return relativePath.startsWith("backend/")
                ? relativePath.substring("backend/".length())
                : relativePath;
    }

    private boolean isBackendFile(String relativePath) {
        return relativePath.endsWith(".go")
                || relativePath.endsWith(".sql")
                || relativePath.equals("go.mod")
                || relativePath.equals("backend/go.mod")
                || relativePath.startsWith("backend/");
    }

    private String safeDiagnosticValue(String value, String fallback) {
        String sanitized = LogExceptionSanitizer.sanitizeValue(value, 512);
        return StrUtil.isBlank(sanitized) ? fallback : sanitized;
    }

    private String normalizePath(String relativePath) {
        return StrUtil.blankToDefault(relativePath, "").trim().replace('\\', '/');
    }
}
