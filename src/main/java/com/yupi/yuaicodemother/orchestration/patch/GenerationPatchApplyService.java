package com.yupi.yuaicodemother.orchestration.patch;

import cn.hutool.core.util.StrUtil;
import com.yupi.yuaicodemother.orchestration.artifact.ChangePlan;
import com.yupi.yuaicodemother.orchestration.artifact.GenerationArtifact;
import com.yupi.yuaicodemother.orchestration.artifact.PatchApplyResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 受 ChangePlan 约束的本地文件级补丁执行器。
 */
@Slf4j
@Component
public class GenerationPatchApplyService {

    private static final Set<String> SUPPORTED_ACTIONS = Set.of(
            PatchOperation.ACTION_ADD,
            PatchOperation.ACTION_MODIFY,
            PatchOperation.ACTION_REPLACE,
            PatchOperation.ACTION_DELETE
    );

    public PatchApplyResult apply(Long appId,
                                  String taskId,
                                  Path projectRoot,
                                  GenerationArtifact changePlanArtifact,
                                  List<PatchOperation> operations) {
        if (changePlanArtifact == null || changePlanArtifact.payload() == null || changePlanArtifact.payload().isEmpty()) {
            return PatchApplyResult.skipped(appId, taskId, normalizeRoot(projectRoot) == null ? "" : normalizeRoot(projectRoot).toString(), "change_plan_missing");
        }
        return apply(appId, taskId, projectRoot, ChangePlan.fromPayload(payload(changePlanArtifact)), operations);
    }

    public PatchApplyResult apply(Long appId,
                                  String taskId,
                                  Path projectRoot,
                                  ChangePlan changePlan,
                                  List<PatchOperation> operations) {
        Path normalizedRoot = normalizeRoot(projectRoot);
        String projectPath = pathToString(normalizedRoot);
        if (normalizedRoot == null || !Files.isDirectory(normalizedRoot)) {
            return PatchApplyResult.skipped(appId, taskId, projectPath, "project_root_missing");
        }
        if (changePlan == null) {
            return PatchApplyResult.skipped(appId, taskId, projectPath, "change_plan_missing");
        }
        if ("project_bootstrap".equals(changePlan.changeScope())) {
            return PatchApplyResult.skipped(appId, taskId, projectPath, "project_bootstrap_not_patch_first");
        }
        if (operations == null || operations.isEmpty()) {
            return PatchApplyResult.skipped(appId, taskId, projectPath, "patch_operations_empty");
        }
        ValidationResult validationResult = validate(normalizedRoot, changePlan, operations);
        if (!validationResult.rejectedOperations().isEmpty()) {
            return PatchApplyResult.rejected(
                    appId,
                    taskId,
                    projectPath,
                    operations.size(),
                    validationResult.rejectedOperations(),
                    "patch_operation_validation_failed"
            );
        }
        try {
            List<String> appliedFiles = applyValidatedOperations(validationResult.validOperations());
            return PatchApplyResult.applied(appId, taskId, projectPath, operations.size(), appliedFiles);
        } catch (Exception e) {
            log.warn("本地补丁执行失败，appId: {}, taskId: {}", appId, taskId, e);
            return PatchApplyResult.rejected(
                    appId,
                    taskId,
                    projectPath,
                    operations.size(),
                    List.of("executor:" + e.getMessage()),
                    "patch_apply_failed"
            );
        }
    }

    public String renderText(PatchApplyResult result) {
        if (result == null) {
            return "补丁执行结果不可用";
        }
        if ("applied".equals(result.status())) {
            return "补丁执行成功，已落盘 " + result.appliedOperationCount() + " 个操作。";
        }
        if ("rejected".equals(result.status())) {
            return "补丁执行已拒绝，原因: " + result.reason() + "，拒绝操作 " + result.rejectedOperationCount() + " 个。";
        }
        return "补丁执行已跳过: " + result.reason();
    }

    private ValidationResult validate(Path projectRoot, ChangePlan changePlan, List<PatchOperation> operations) {
        List<ValidatedOperation> validOperations = new ArrayList<>();
        List<String> rejectedOperations = new ArrayList<>();
        Set<String> seenPaths = new LinkedHashSet<>();
        for (PatchOperation operation : operations) {
            String action = operation == null ? "" : operation.action();
            String normalizedPath = normalizePath(operation == null ? "" : operation.relativePath());
            String operationLabel = StrUtil.blankToDefault(action, "unknown") + ":" + StrUtil.blankToDefault(normalizedPath, "");
            if (!SUPPORTED_ACTIONS.contains(action)) {
                rejectedOperations.add(operationLabel + ":unsupported_action");
                continue;
            }
            if (StrUtil.isBlank(normalizedPath)) {
                rejectedOperations.add(operationLabel + ":invalid_path");
                continue;
            }
            if (!seenPaths.add(normalizedPath)) {
                rejectedOperations.add(operationLabel + ":duplicate_operation_path");
                continue;
            }
            if (!isPlanned(changePlan, action, normalizedPath)) {
                rejectedOperations.add(operationLabel + ":outside_change_plan");
                continue;
            }
            Path targetPath = projectRoot.resolve(normalizedPath).toAbsolutePath().normalize();
            if (!targetPath.startsWith(projectRoot)) {
                rejectedOperations.add(operationLabel + ":path_outside_project");
                continue;
            }
            String blocker = validateTarget(action, operation, targetPath);
            if (StrUtil.isNotBlank(blocker)) {
                rejectedOperations.add(operationLabel + ":" + blocker);
                continue;
            }
            validOperations.add(new ValidatedOperation(action, normalizedPath, targetPath, operation));
        }
        return new ValidationResult(validOperations, rejectedOperations);
    }

    private List<String> applyValidatedOperations(List<ValidatedOperation> operations) throws IOException {
        List<String> appliedFiles = new ArrayList<>();
        for (ValidatedOperation operation : operations) {
            PatchOperation patchOperation = operation.operation();
            Path parent = operation.targetPath().getParent();
            if (parent != null && !PatchOperation.ACTION_DELETE.equals(operation.action())) {
                Files.createDirectories(parent);
            }
            switch (operation.action()) {
                case PatchOperation.ACTION_ADD -> Files.writeString(
                        operation.targetPath(),
                        patchOperation.content(),
                        StandardCharsets.UTF_8,
                        StandardOpenOption.WRITE,
                        StandardOpenOption.CREATE_NEW
                );
                case PatchOperation.ACTION_MODIFY -> Files.writeString(
                        operation.targetPath(),
                        patchOperation.content(),
                        StandardCharsets.UTF_8,
                        StandardOpenOption.WRITE,
                        StandardOpenOption.TRUNCATE_EXISTING
                );
                case PatchOperation.ACTION_REPLACE -> {
                    String originalContent = Files.readString(operation.targetPath(), StandardCharsets.UTF_8);
                    String modifiedContent = originalContent.replace(patchOperation.oldContent(), patchOperation.newContent());
                    Files.writeString(
                            operation.targetPath(),
                            modifiedContent,
                            StandardCharsets.UTF_8,
                            StandardOpenOption.WRITE,
                            StandardOpenOption.TRUNCATE_EXISTING
                    );
                }
                case PatchOperation.ACTION_DELETE -> Files.delete(operation.targetPath());
                default -> throw new IllegalStateException("Unsupported patch action: " + operation.action());
            }
            appliedFiles.add(operation.action() + ":" + operation.relativePath());
        }
        return appliedFiles;
    }

    private String validateTarget(String action, PatchOperation operation, Path targetPath) {
        if (PatchOperation.ACTION_ADD.equals(action)) {
            if (operation.content() == null) {
                return "content_missing";
            }
            return Files.exists(targetPath) ? "add_target_already_exists" : "";
        }
        if (PatchOperation.ACTION_MODIFY.equals(action)) {
            if (operation.content() == null) {
                return "content_missing";
            }
            return Files.isRegularFile(targetPath) ? "" : "modify_target_missing";
        }
        if (PatchOperation.ACTION_REPLACE.equals(action)) {
            if (StrUtil.isBlank(operation.oldContent()) || operation.newContent() == null) {
                return "replace_content_missing";
            }
            if (!Files.isRegularFile(targetPath)) {
                return "replace_target_missing";
            }
            try {
                String originalContent = Files.readString(targetPath, StandardCharsets.UTF_8);
                return originalContent.contains(operation.oldContent()) ? "" : "old_content_not_found";
            } catch (IOException e) {
                return "read_target_failed";
            }
        }
        if (PatchOperation.ACTION_DELETE.equals(action)) {
            return Files.isRegularFile(targetPath) ? "" : "delete_target_missing";
        }
        return "unsupported_action";
    }

    private boolean isPlanned(ChangePlan changePlan, String action, String normalizedPath) {
        return switch (action) {
            case PatchOperation.ACTION_ADD -> changePlan.addFiles().contains(normalizedPath);
            case PatchOperation.ACTION_MODIFY, PatchOperation.ACTION_REPLACE ->
                    changePlan.modifyFiles().contains(normalizedPath);
            case PatchOperation.ACTION_DELETE -> changePlan.deleteFiles().contains(normalizedPath);
            default -> false;
        };
    }

    private String normalizePath(String path) {
        List<String> normalized = ChangePlan.normalizeFilePaths(List.of(StrUtil.blankToDefault(path, "")));
        return normalized.isEmpty() ? "" : normalized.getFirst();
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

    private record ValidatedOperation(String action, String relativePath, Path targetPath, PatchOperation operation) {
    }

    private record ValidationResult(List<ValidatedOperation> validOperations, List<String> rejectedOperations) {
    }
}
