package com.rush.rushaicodemother.orchestration.edit;

import cn.hutool.core.util.StrUtil;
import com.rush.rushaicodemother.ai.model.EditResult;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.patch.PatchOperation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

/**
 * 编辑验证策略服务。
 * 根据 patch 操作、变更文件和 AI 建议决定验证级别。
 */
@Slf4j
@Service
public class EditValidationPolicyService {

    /**
     * 无需构建的文件扩展名（文案、样式、静态数据）
     */
    private static final Set<String> NO_BUILD_EXTENSIONS = Set.of(
            "css", "scss", "less", "svg", "png", "jpg", "jpeg", "gif", "ico", "woff", "woff2", "ttf", "eot"
    );

    /**
     * 无需构建的文件名模式（纯文案内容）
     */
    private static final Set<String> NO_BUILD_FILE_PATTERNS = Set.of(
            "README", "LICENSE", "CHANGELOG", ".md", ".txt"
    );

    /**
     * 快速检查的文件扩展名（Vue 单文件组件、TS/JS 小函数）
     */
    private static final Set<String> FAST_CHECK_EXTENSIONS = Set.of(
            "vue", "jsx", "tsx", "js", "ts"
    );

    /**
     * 必须构建的文件名模式（配置文件、入口文件）
     */
    private static final Set<String> BUILD_REQUIRED_FILE_PATTERNS = Set.of(
            "package.json", "vite.config", "tsconfig", "webpack.config", "rollup.config",
            "go.mod", "go.sum", "Dockerfile", "docker-compose", ".env",
            "router", "routes", "api", "main", "index"
    );

    /**
     * 必须构建的文件扩展名
     */
    private static final Set<String> BUILD_REQUIRED_EXTENSIONS = Set.of(
            "mod", "sum", "lock"
    );

    /**
     * 最大快速检查文件数（超过则需要构建）
     */
    private static final int MAX_FAST_CHECK_FILES = 5;

    /**
     * 最大轻量编辑文件数（超过则需要完整审查）
     */
    private static final int MAX_LIGHTWEIGHT_FILES = 8;

    /**
     * 确定验证计划。
     *
     * @param patchOperations  补丁操作列表
     * @param codeGenType      代码生成类型
     * @param aiValidation     AI 建议的验证级别
     * @return 验证计划
     */
    public EditValidationPlan determineValidationPlan(
            List<PatchOperation> patchOperations,
            CodeGenTypeEnum codeGenType,
            EditResult.EditValidation aiValidation) {

        if (patchOperations == null || patchOperations.isEmpty()) {
            return new EditValidationPlan(
                    EditValidationPlan.ValidationLevel.NONE,
                    "无补丁操作",
                    List.of(),
                    false
            );
        }

        List<String> changedFiles = patchOperations.stream()
                .map(PatchOperation::relativePath)
                .filter(StrUtil::isNotBlank)
                .toList();

        // 检查是否需要构建
        boolean aiSuggestedBuild = aiValidation != null && aiValidation.requiresBuild();

        // 分析变更文件
        ValidationAnalysis analysis = analyzeChangedFiles(changedFiles);

        // 确定验证级别
        EditValidationPlan.ValidationLevel level;
        String reason;

        if (analysis.hasBuildRequiredFiles()) {
            level = EditValidationPlan.ValidationLevel.BUILD_REQUIRED;
            reason = "包含必须构建的文件: " + String.join(", ", analysis.buildRequiredFiles());
        } else if (changedFiles.size() > MAX_LIGHTWEIGHT_FILES) {
            level = EditValidationPlan.ValidationLevel.HEAVY_REVIEW_REQUIRED;
            reason = "变更文件数量过多: " + changedFiles.size();
        } else if (aiSuggestedBuild) {
            level = EditValidationPlan.ValidationLevel.BUILD_REQUIRED;
            reason = "AI 建议构建验证";
        } else if (analysis.hasOnlyNoBuildFiles()) {
            level = EditValidationPlan.ValidationLevel.NONE;
            reason = "仅包含无需构建的文件（文案、样式、静态资源）";
        } else if (changedFiles.size() <= MAX_FAST_CHECK_FILES && analysis.hasOnlyFastCheckFiles()) {
            level = EditValidationPlan.ValidationLevel.FAST_CHECK;
            reason = "少量 Vue/JS/TS 文件变更，可快速检查";
        } else {
            level = EditValidationPlan.ValidationLevel.BUILD_REQUIRED;
            reason = "默认需要构建验证";
        }

        log.debug("验证计划确定: level={}, reason={}, changedFiles={}", level, reason, changedFiles);

        return new EditValidationPlan(level, reason, changedFiles, aiSuggestedBuild);
    }

    /**
     * 分析变更文件。
     */
    private ValidationAnalysis analyzeChangedFiles(List<String> changedFiles) {
        boolean hasBuildRequiredFiles = false;
        boolean hasOnlyNoBuildFiles = true;
        boolean hasOnlyFastCheckFiles = true;
        var buildRequiredFiles = new java.util.ArrayList<String>();

        for (String filePath : changedFiles) {
            if (StrUtil.isBlank(filePath)) {
                continue;
            }

            String normalizedPath = filePath.toLowerCase();
            String fileName = getFileName(normalizedPath);
            String extension = getFileExtension(normalizedPath);

            // 检查是否为必须构建的文件
            if (isBuildRequiredFile(fileName, normalizedPath, extension)) {
                hasBuildRequiredFiles = true;
                buildRequiredFiles.add(filePath);
                hasOnlyNoBuildFiles = false;
                hasOnlyFastCheckFiles = false;
                continue;
            }

            // 检查是否为无需构建的文件
            if (isNoBuildFile(fileName, normalizedPath, extension)) {
                // 保持 hasOnlyNoBuildFiles 为 true
            } else {
                hasOnlyNoBuildFiles = false;
            }

            // 检查是否为快速检查文件
            if (!isFastCheckFile(extension)) {
                hasOnlyFastCheckFiles = false;
            }
        }

        return new ValidationAnalysis(hasBuildRequiredFiles, hasOnlyNoBuildFiles, hasOnlyFastCheckFiles, buildRequiredFiles);
    }

    /**
     * 检查是否为必须构建的文件。
     */
    private boolean isBuildRequiredFile(String fileName, String normalizedPath, String extension) {
        // 检查文件名模式
        for (String pattern : BUILD_REQUIRED_FILE_PATTERNS) {
            if (fileName.contains(pattern) || normalizedPath.contains(pattern)) {
                return true;
            }
        }

        // 检查扩展名
        return BUILD_REQUIRED_EXTENSIONS.contains(extension);
    }

    /**
     * 检查是否为无需构建的文件。
     */
    private boolean isNoBuildFile(String fileName, String normalizedPath, String extension) {
        // 检查扩展名
        if (NO_BUILD_EXTENSIONS.contains(extension)) {
            return true;
        }

        // 检查文件名模式
        for (String pattern : NO_BUILD_FILE_PATTERNS) {
            if (fileName.contains(pattern) || normalizedPath.contains(pattern)) {
                return true;
            }
        }

        return false;
    }

    /**
     * 检查是否为快速检查文件。
     */
    private boolean isFastCheckFile(String extension) {
        return FAST_CHECK_EXTENSIONS.contains(extension);
    }

    /**
     * 获取文件名。
     */
    private String getFileName(String path) {
        if (StrUtil.isBlank(path)) {
            return "";
        }
        int lastSlash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
    }

    /**
     * 获取文件扩展名。
     */
    private String getFileExtension(String path) {
        String fileName = getFileName(path);
        int lastDot = fileName.lastIndexOf('.');
        return lastDot >= 0 ? fileName.substring(lastDot + 1).toLowerCase() : "";
    }

    /**
     * 验证分析结果。
     */
    private record ValidationAnalysis(
            boolean hasBuildRequiredFiles,
            boolean hasOnlyNoBuildFiles,
            boolean hasOnlyFastCheckFiles,
            List<String> buildRequiredFiles
    ) {
    }
}
