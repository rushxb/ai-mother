package com.rush.rushaicodemother.orchestration.edit;

import cn.hutool.core.util.StrUtil;
import com.rush.rushaicodemother.orchestration.patch.PatchOperation;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Service
public class AgentEditBackendValidationService {

    public BackgroundValidationService.ValidationResult validate(String taskId,
                                                                 GenerationWorkspace workspace,
                                                                 List<PatchOperation> patchOperations) {
        if (workspace == null || patchOperations == null || patchOperations.isEmpty()) {
            return BackgroundValidationService.ValidationResult.skipped(taskId, "无后端补丁需要验证");
        }
        Path root = workspace.backendRootPath() == null ? workspace.canonicalRootPath() : workspace.backendRootPath();
        List<String> errors = new ArrayList<>();
        for (PatchOperation operation : patchOperations) {
            String relativePath = normalizePath(operation.relativePath());
            if (StrUtil.isBlank(relativePath) || !isBackendFile(relativePath)) {
                continue;
            }
            Path target = resolveBackendPath(root, relativePath);
            if (!Files.isRegularFile(target)) {
                errors.add(relativePath + ":文件不存在");
                continue;
            }
            try {
                String content = Files.readString(target, StandardCharsets.UTF_8);
                if (relativePath.endsWith(".go")) {
                    validateGo(relativePath, content, errors);
                } else if (relativePath.endsWith(".sql")) {
                    validateSql(relativePath, content, errors);
                }
            } catch (Exception e) {
                errors.add(relativePath + ":读取失败:" + e.getMessage());
            }
        }
        if (!errors.isEmpty()) {
            return BackgroundValidationService.ValidationResult.failed(taskId, "后端轻量验证失败: " + String.join("; ", errors));
        }
        return BackgroundValidationService.ValidationResult.success(taskId, "后端轻量验证通过");
    }

    private void validateGo(String relativePath, String content, List<String> errors) {
        String trimmed = StrUtil.blankToDefault(content, "").stripLeading();
        if (!trimmed.startsWith("package ")) {
            errors.add(relativePath + ":缺少 package 声明");
        }
        if (!balanced(content, '{', '}')) {
            errors.add(relativePath + ":花括号不平衡");
        }
        if (!balanced(content, '(', ')')) {
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
        for (int i = 0; i < StrUtil.blankToDefault(content, "").length(); i++) {
            char c = content.charAt(i);
            if (c == open) {
                depth++;
            } else if (c == close) {
                depth--;
                if (depth < 0) {
                    return false;
                }
            }
        }
        return depth == 0;
    }

    private Path resolveBackendPath(Path backendRoot, String relativePath) {
        if (relativePath.startsWith("backend/")) {
            return backendRoot.resolve(relativePath.substring("backend/".length())).toAbsolutePath().normalize();
        }
        return backendRoot.resolve(relativePath).toAbsolutePath().normalize();
    }

    private boolean isBackendFile(String relativePath) {
        return relativePath.endsWith(".go")
                || relativePath.endsWith(".sql")
                || relativePath.equals("go.mod")
                || relativePath.equals("backend/go.mod")
                || relativePath.startsWith("backend/");
    }

    private String normalizePath(String relativePath) {
        return StrUtil.blankToDefault(relativePath, "").trim().replace('\\', '/');
    }
}
