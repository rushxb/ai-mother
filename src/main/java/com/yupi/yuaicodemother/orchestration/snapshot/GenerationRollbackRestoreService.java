package com.yupi.yuaicodemother.orchestration.snapshot;

import com.yupi.yuaicodemother.constant.AppConstant;
import com.yupi.yuaicodemother.orchestration.artifact.ChangePlan;
import com.yupi.yuaicodemother.orchestration.artifact.GenerationArtifact;
import com.yupi.yuaicodemother.orchestration.artifact.RollbackRestore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 失败后按 opt-in 回滚策略恢复本地快照。
 */
@Slf4j
@Component
public class GenerationRollbackRestoreService {

    private static final DateTimeFormatter BACKUP_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
    private static final Set<String> DEFAULT_IGNORED_NAMES = Set.of(
            ".git", ".idea", ".vscode", "node_modules", "dist", "build", "target", "coverage"
    );

    private final Path codeOutputRoot;
    private final Path codeSnapshotRoot;

    public GenerationRollbackRestoreService() {
        this(Path.of(AppConstant.CODE_OUTPUT_ROOT_DIR), Path.of(AppConstant.CODE_SNAPSHOT_ROOT_DIR));
    }

    public GenerationRollbackRestoreService(Path codeOutputRoot, Path codeSnapshotRoot) {
        this.codeOutputRoot = codeOutputRoot.toAbsolutePath().normalize();
        this.codeSnapshotRoot = codeSnapshotRoot.toAbsolutePath().normalize();
    }

    public GenerationArtifact restoreIfAllowed(Long appId,
                                               String taskId,
                                               GenerationArtifact changePlanArtifact,
                                               GenerationArtifact rollbackPointArtifact) {
        RollbackRestore restore = restore(appId, taskId, changePlanArtifact, rollbackPointArtifact);
        return GenerationArtifact.of("rollback_restore", "Orchestrator", "失败后本地回滚结果", restore.toPayload());
    }

    RollbackRestore restore(Long appId,
                            String taskId,
                            GenerationArtifact changePlanArtifact,
                            GenerationArtifact rollbackPointArtifact) {
        ChangePlan changePlan = ChangePlan.fromPayload(payload(changePlanArtifact));
        String rollbackStrategy = changePlan == null ? "manual_retry_without_snapshot" : changePlan.rollbackStrategy();
        if (changePlan == null || !changePlan.requiresSnapshotRollback()) {
            return RollbackRestore.skipped(appId, taskId, rollbackStrategy, "", "", "rollback_strategy_not_snapshot");
        }
        Map<String, Object> rollbackPayload = payload(rollbackPointArtifact);
        String rollbackStatus = stringValue(rollbackPayload.get("status"));
        String snapshotPathValue = stringValue(rollbackPayload.get("snapshotPath"));
        String projectPathValue = stringValue(rollbackPayload.get("projectPath"));
        if (!"created".equals(rollbackStatus)) {
            return RollbackRestore.skipped(
                    appId,
                    taskId,
                    rollbackStrategy,
                    snapshotPathValue,
                    projectPathValue,
                    "rollback_point_not_created"
            );
        }
        if (snapshotPathValue.isBlank() || projectPathValue.isBlank()) {
            return RollbackRestore.skipped(
                    appId,
                    taskId,
                    rollbackStrategy,
                    snapshotPathValue,
                    projectPathValue,
                    "rollback_point_path_missing"
            );
        }

        Path snapshotPath = Path.of(snapshotPathValue).toAbsolutePath().normalize();
        Path projectPath = Path.of(projectPathValue).toAbsolutePath().normalize();
        if (!snapshotPath.startsWith(codeSnapshotRoot) || !projectPath.startsWith(codeOutputRoot)) {
            return RollbackRestore.skipped(
                    appId,
                    taskId,
                    rollbackStrategy,
                    snapshotPathValue,
                    projectPathValue,
                    "rollback_path_out_of_root"
            );
        }
        if (!Files.isDirectory(snapshotPath)) {
            return RollbackRestore.skipped(appId, taskId, rollbackStrategy, snapshotPathValue, projectPathValue, "snapshot_missing");
        }
        if (!Files.exists(projectPath.getParent())) {
            return RollbackRestore.skipped(appId, taskId, rollbackStrategy, snapshotPathValue, projectPathValue, "project_parent_missing");
        }

        Path backupPath = backupPath(appId, taskId);
        try {
            String backupPathValue = "";
            if (Files.exists(projectPath)) {
                ensureChildOf(projectPath.getParent(), projectPath);
                copyProject(projectPath, backupPath);
                backupPathValue = backupPath.toString();
                deleteProject(projectPath);
            }
            copyProject(snapshotPath, projectPath);
            int restoredFileCount = listProjectFiles(projectPath).size();
            return RollbackRestore.restored(
                    appId,
                    taskId,
                    rollbackStrategy,
                    snapshotPath.toString(),
                    projectPath.toString(),
                    backupPathValue,
                    restoredFileCount
            );
        } catch (Exception e) {
            log.warn("失败后本地快照恢复失败，appId: {}, taskId: {}", appId, taskId, e);
            return RollbackRestore.failed(
                    appId,
                    taskId,
                    rollbackStrategy,
                    snapshotPath.toString(),
                    projectPath.toString(),
                    backupPath.toString(),
                    "rollback_restore_failed:" + e.getMessage()
            );
        }
    }

    private Path backupPath(Long appId, String taskId) {
        String appDir = appId == null ? "unknown" : String.valueOf(appId);
        String normalizedTaskId = taskId == null ? "unknown" : taskId.replaceAll("[^a-zA-Z0-9_-]", "_");
        String backupName = "failed_generation_" + normalizedTaskId + "_" + LocalDateTime.now().format(BACKUP_TIME_FORMATTER);
        return codeSnapshotRoot.resolve(appDir)
                .resolve(backupName)
                .toAbsolutePath()
                .normalize();
    }

    private void copyProject(Path sourceRoot, Path targetRoot) throws IOException {
        Files.createDirectories(targetRoot);
        try (Stream<Path> stream = Files.walk(sourceRoot)) {
            List<Path> sourcePaths = stream
                    .filter(sourcePath -> shouldInclude(sourceRoot, sourcePath))
                    .sorted()
                    .toList();
            for (Path sourcePath : sourcePaths) {
                Path relative = sourceRoot.relativize(sourcePath);
                Path targetPath = targetRoot.resolve(relative).toAbsolutePath().normalize();
                ensureChildOf(targetRoot, targetPath);
                if (Files.isDirectory(sourcePath)) {
                    Files.createDirectories(targetPath);
                } else {
                    Files.createDirectories(targetPath.getParent());
                    Files.copy(sourcePath, targetPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        }
    }

    private void deleteProject(Path projectPath) throws IOException {
        try (Stream<Path> stream = Files.walk(projectPath)) {
            List<Path> paths = stream
                    .sorted(Comparator.reverseOrder())
                    .toList();
            for (Path path : paths) {
                Files.deleteIfExists(path);
            }
        }
    }

    private List<Path> listProjectFiles(Path root) throws IOException {
        try (Stream<Path> stream = Files.walk(root)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> shouldInclude(root, path))
                    .map(root::relativize)
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        }
    }

    private boolean shouldInclude(Path root, Path path) {
        Path relative = root.equals(path) ? Path.of("") : root.relativize(path);
        for (Path part : relative.normalize()) {
            if (DEFAULT_IGNORED_NAMES.contains(part.toString())) {
                return false;
            }
        }
        return true;
    }

    private void ensureChildOf(Path root, Path child) {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path normalizedChild = child.toAbsolutePath().normalize();
        if (!normalizedChild.startsWith(normalizedRoot)) {
            throw new IllegalArgumentException("非法路径，超出当前项目目录范围");
        }
    }

    private Map<String, Object> payload(GenerationArtifact artifact) {
        return artifact == null || artifact.payload() == null ? Map.of() : artifact.payload();
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
