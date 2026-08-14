package com.rush.rushaicodemother.orchestration.patch;

import cn.hutool.core.util.StrUtil;
import com.rush.rushaicodemother.orchestration.artifact.ChangePlan;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 验证整个补丁批次而不改变工作区。 */
@Component
@RequiredArgsConstructor
public class PatchOperationValidator {

    private static final Set<String> SUPPORTED_ACTIONS = Set.of(
            PatchOperation.ACTION_ADD,
            PatchOperation.ACTION_MODIFY,
            PatchOperation.ACTION_REPLACE,
            PatchOperation.ACTION_DELETE,
            PatchOperation.ACTION_INSERT_BEFORE_MARKER,
            PatchOperation.ACTION_INSERT_AFTER_MARKER,
            PatchOperation.ACTION_GO_ADD_IMPORT,
            PatchOperation.ACTION_GO_APPEND_TO_FUNCTION,
            PatchOperation.ACTION_GO_ADD_STRUCT_FIELDS,
            PatchOperation.ACTION_APPEND_SQL_MIGRATION
    );

    private final PatchWorkspaceFileService workspaceFileService;
    private final PatchStructuredContentService structuredContentService;
    private final FrontendPatchImportPolicy frontendImportPolicy;
    private final GeneratedWorkspaceTrustPolicy generatedWorkspaceTrustPolicy;

    /**
 * 校验{@code ate}是否有效。
 *
 * @param projectRoot 项目根
 * @param changePlan {@code changePlan} 对应的调用参数
 * @param operations 操作
 * @return {@code ate}
 */
    public PatchValidationResult validate(Path projectRoot,
                                          ChangePlan changePlan,
                                          List<PatchOperation> operations) {
        List<ValidatedPatchOperation> validOperations = new ArrayList<>();
        List<String> rejectedOperations = new ArrayList<>();
        Set<String> seenPaths = new LinkedHashSet<>();
        // 按既定顺序逐项处理，并在达到资源或状态边界时提前结束。
        for (PatchOperation operation : operations) {
            String action = operation == null ? "" : operation.action();
            String normalizedPath = normalizePath(operation == null ? "" : operation.relativePath());
            String operationLabel = StrUtil.blankToDefault(action, "unknown")
                    + ":" + StrUtil.blankToDefault(normalizedPath, "");
            if (!SUPPORTED_ACTIONS.contains(action)) {
                rejectedOperations.add(operationLabel + ":unsupported_action");
                continue;
            }
            if (StrUtil.isBlank(normalizedPath)) {
                rejectedOperations.add(operationLabel + ":invalid_path");
                continue;
            }
            if (changePlan != null && !seenPaths.add(normalizedPath)) {
                rejectedOperations.add(operationLabel + ":duplicate_operation_path");
                continue;
            }
            if (changePlan != null && !isPlanned(changePlan, action, normalizedPath)) {
                rejectedOperations.add(operationLabel + ":outside_change_plan");
                continue;
            }
            PatchWorkspaceTarget target;
            try {
                target = workspaceFileService.resolve(projectRoot, operation.relativePath());
            } catch (PatchWorkspaceException exception) {
                rejectedOperations.add(operationLabel + ":" + exception.reason());
                continue;
            }
            String targetBlocker = validateTarget(action, operation, target);
            if (StrUtil.isNotBlank(targetBlocker)) {
                rejectedOperations.add(operationLabel + ":" + targetBlocker);
                continue;
            }
            String workspaceTrustBlocker = validateGeneratedWorkspaceTrust(
                    action, operation, normalizedPath, target);
            if (StrUtil.isNotBlank(workspaceTrustBlocker)) {
                rejectedOperations.add(operationLabel + ":" + workspaceTrustBlocker);
                continue;
            }
            String dependencyBlocker = frontendImportPolicy.validate(
                    projectRoot, action, operation, normalizedPath, target);
            if (StrUtil.isNotBlank(dependencyBlocker)) {
                rejectedOperations.add(operationLabel + ":" + dependencyBlocker);
                continue;
            }
            validOperations.add(new ValidatedPatchOperation(action, normalizedPath, target, operation));
        }
        return new PatchValidationResult(validOperations, rejectedOperations);
    }

    private String validateGeneratedWorkspaceTrust(String action,
                                                   PatchOperation operation,
                                                   String normalizedPath,
                                                   PatchWorkspaceTarget target) {
        if (!generatedWorkspaceTrustPolicy.appliesTo(normalizedPath)) {
            return "";
        }
        if (PatchOperation.ACTION_DELETE.equals(action)) {
            return generatedWorkspaceTrustPolicy.validateDeletion(normalizedPath);
        }
        try {
            return generatedWorkspaceTrustPolicy.validate(
                    normalizedPath,
                    candidateContent(action, operation, target));
        } catch (IOException exception) {
            return "executable_manifest_read_failed";
        }
    }

    private String candidateContent(String action,
                                    PatchOperation operation,
                                    PatchWorkspaceTarget target) throws IOException {
        if (PatchOperation.ACTION_ADD.equals(action)
                || PatchOperation.ACTION_MODIFY.equals(action)) {
            return operation.content();
        }
        if (PatchOperation.ACTION_REPLACE.equals(action)
                || PatchOperation.ACTION_INSERT_BEFORE_MARKER.equals(action)
                || PatchOperation.ACTION_INSERT_AFTER_MARKER.equals(action)) {
            String originalContent = workspaceFileService.readUtf8(target);
            String replacement = operation.newContent();
            if (PatchOperation.ACTION_INSERT_BEFORE_MARKER.equals(action)) {
                replacement = operation.newContent() + System.lineSeparator() + operation.oldContent();
            } else if (PatchOperation.ACTION_INSERT_AFTER_MARKER.equals(action)) {
                replacement = operation.oldContent() + System.lineSeparator() + operation.newContent();
            }
            return originalContent.replace(operation.oldContent(), replacement);
        }
        return null;
    }

    /** 校验{@code ate}目标是否有效。 */
    private String validateTarget(String action,
                                  PatchOperation operation,
                                  PatchWorkspaceTarget target) {
        try {
            return validateTargetSafely(action, operation, target);
        } catch (PatchWorkspaceException exception) {
            return exception.reason();
        } catch (IOException exception) {
            return "read_target_failed";
        }
    }

    /** 校验{@code ate}目标安全处理是否有效。 */
    private String validateTargetSafely(String action,
                                        PatchOperation operation,
                                        PatchWorkspaceTarget target) throws IOException {
        // 先处理前置条件和快速返回分支，避免无效输入进入核心流程。
        if (PatchOperation.ACTION_ADD.equals(action)) {
            if (operation.content() == null) {
                return "content_missing";
            }
            workspaceFileService.validateWritableUtf8(operation.content());
            return workspaceFileService.exists(target) ? "add_target_already_exists" : "";
        }
        if (PatchOperation.ACTION_MODIFY.equals(action)) {
            if (operation.content() == null) {
                return "content_missing";
            }
            workspaceFileService.validateWritableUtf8(operation.content());
            return workspaceFileService.isRegularFile(target) ? "" : "modify_target_missing";
        }
        if (PatchOperation.ACTION_REPLACE.equals(action)) {
            return validateReplacement(operation, target, "replace_content_missing", "replace_target_missing",
                    "old_content_not_found", false);
        }
        if (PatchOperation.ACTION_INSERT_BEFORE_MARKER.equals(action)
                || PatchOperation.ACTION_INSERT_AFTER_MARKER.equals(action)) {
            return validateReplacement(operation, target, "marker_content_missing", "marker_target_missing",
                    "marker_not_found", true);
        }
        if (PatchOperation.ACTION_GO_ADD_IMPORT.equals(action)) {
            return validateStructuredOperation(action, operation, target, ".go", "go_import_content_missing");
        }
        if (PatchOperation.ACTION_GO_APPEND_TO_FUNCTION.equals(action)
                || PatchOperation.ACTION_GO_ADD_STRUCT_FIELDS.equals(action)) {
            if (StrUtil.isBlank(operation.oldContent()) || StrUtil.isBlank(operation.newContent())) {
                return "go_structured_content_missing";
            }
            return validateStructuredOperation(action, operation, target, ".go", "go_structured_target_missing");
        }
        if (PatchOperation.ACTION_APPEND_SQL_MIGRATION.equals(action)) {
            if (structuredContentService.containsDangerousSql(operation.newContent())) {
                return "dangerous_sql_migration";
            }
            return validateStructuredOperation(action, operation, target, ".sql", "sql_migration_content_missing");
        }
        if (PatchOperation.ACTION_DELETE.equals(action)) {
            return workspaceFileService.isRegularFile(target) ? "" : "delete_target_missing";
        }
        return "unsupported_action";
    }

    /** 校验{@code ate}替换内容是否有效。 */
    private String validateReplacement(PatchOperation operation,
                                       PatchWorkspaceTarget target,
                                       String contentMissingReason,
                                       String targetMissingReason,
                                       String sourceMissingReason,
                                       boolean insertAroundMarker) throws IOException {
        if (StrUtil.isBlank(operation.oldContent()) || operation.newContent() == null) {
            return contentMissingReason;
        }
        if (!workspaceFileService.isRegularFile(target)) {
            return targetMissingReason;
        }
        String originalContent = workspaceFileService.readUtf8(target);
        if (!originalContent.contains(operation.oldContent())) {
            return sourceMissingReason;
        }
        String replacement = operation.newContent();
        if (insertAroundMarker) {
            replacement = PatchOperation.ACTION_INSERT_BEFORE_MARKER.equals(operation.action())
                    ? operation.newContent() + System.lineSeparator() + operation.oldContent()
                    : operation.oldContent() + System.lineSeparator() + operation.newContent();
        }
        workspaceFileService.validateWritableUtf8(
                originalContent.replace(operation.oldContent(), replacement));
        return "";
    }

    /** 校验{@code ate}{@code Structured}操作是否有效。 */
    private String validateStructuredOperation(String action,
                                               PatchOperation operation,
                                               PatchWorkspaceTarget target,
                                               String extension,
                                               String missingContentReason) throws IOException {
        if (StrUtil.isBlank(operation.newContent())) {
            return missingContentReason;
        }
        if (!workspaceFileService.isRegularFile(target)) {
            return "structured_target_missing";
        }
        if (!workspaceFileService.fileName(target).endsWith(extension)) {
            return "structured_target_extension_mismatch";
        }
        String originalContent = workspaceFileService.readUtf8(target);
        String updatedContent = structuredContentService.transform(action, originalContent, operation);
        workspaceFileService.validateWritableUtf8(updatedContent);
        return "";
    }

    /** 判断{@code Planned}是否满足约束。 */
    private boolean isPlanned(ChangePlan changePlan, String action, String path) {
        return switch (action) {
            case PatchOperation.ACTION_ADD -> changePlan.addFiles().contains(path);
            case PatchOperation.ACTION_MODIFY,
                 PatchOperation.ACTION_REPLACE,
                 PatchOperation.ACTION_INSERT_BEFORE_MARKER,
                 PatchOperation.ACTION_INSERT_AFTER_MARKER,
                 PatchOperation.ACTION_GO_ADD_IMPORT,
                 PatchOperation.ACTION_GO_APPEND_TO_FUNCTION,
                 PatchOperation.ACTION_GO_ADD_STRUCT_FIELDS,
                 PatchOperation.ACTION_APPEND_SQL_MIGRATION -> changePlan.modifyFiles().contains(path);
            case PatchOperation.ACTION_DELETE -> changePlan.deleteFiles().contains(path);
            default -> false;
        };
    }

    private String normalizePath(String path) {
        List<String> normalized = ChangePlan.normalizeFilePaths(List.of(StrUtil.blankToDefault(path, "")));
        return normalized.isEmpty() ? "" : normalized.getFirst();
    }
}
