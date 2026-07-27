package com.rush.rushaicodemother.orchestration.edit;

import cn.hutool.core.util.StrUtil;
import com.rush.rushaicodemother.config.PatchExecutionProperties;
import com.rush.rushaicodemother.infrastructure.diagnostic.LogExceptionSanitizer;
import com.rush.rushaicodemother.orchestration.patch.PatchOperation;
import com.rush.rushaicodemother.orchestration.patch.PatchWorkspaceException;
import com.rush.rushaicodemother.orchestration.patch.PatchWorkspaceFileService;
import com.rush.rushaicodemother.orchestration.patch.PatchWorkspaceTarget;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

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

    public BackgroundValidationService.ValidationResult validate(String taskId,
                                                                 GenerationWorkspace workspace,
                                                                 List<PatchOperation> patchOperations) {
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
        return BackgroundValidationService.ValidationResult.success(taskId, "后端轻量验证通过");
    }

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
        String lower = StrUtil.blankToDefault(content, "").toLowerCase();
        if (lower.contains("drop database")
                || lower.contains("drop table")
                || lower.contains("truncate table")
                || lower.contains("pragma writable_schema")) {
            errors.add(relativePath + ":包含危险 SQL");
        }
    }

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
