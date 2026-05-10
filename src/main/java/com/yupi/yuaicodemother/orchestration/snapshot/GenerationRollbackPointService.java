package com.yupi.yuaicodemother.orchestration.snapshot;

import com.yupi.yuaicodemother.constant.AppConstant;
import com.yupi.yuaicodemother.model.entity.App;
import com.yupi.yuaicodemother.model.enums.CodeGenTypeEnum;
import com.yupi.yuaicodemother.orchestration.GenerationOrchestrationRequest;
import com.yupi.yuaicodemother.orchestration.artifact.GenerationArtifact;
import com.yupi.yuaicodemother.orchestration.artifact.RollbackPoint;
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
import java.util.Set;
import java.util.stream.Stream;

/**
 * 在进入真实代码生成前创建本地回滚点。
 */
@Slf4j
@Component
public class GenerationRollbackPointService {

    private static final DateTimeFormatter SNAPSHOT_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
    private static final Set<String> DEFAULT_IGNORED_NAMES = Set.of(
            ".git", ".idea", ".vscode", "node_modules", "dist", "build", "target", "coverage"
    );
    private final Path codeOutputRoot;
    private final Path codeSnapshotRoot;

    public GenerationRollbackPointService() {
        this(Path.of(AppConstant.CODE_OUTPUT_ROOT_DIR), Path.of(AppConstant.CODE_SNAPSHOT_ROOT_DIR));
    }

    public GenerationRollbackPointService(Path codeOutputRoot, Path codeSnapshotRoot) {
        this.codeOutputRoot = codeOutputRoot.toAbsolutePath().normalize();
        this.codeSnapshotRoot = codeSnapshotRoot.toAbsolutePath().normalize();
    }

    public GenerationArtifact prepareRollbackPoint(GenerationOrchestrationRequest request,
                                                   CodeGenTypeEnum targetType,
                                                   String taskId) {
        RollbackPoint rollbackPoint = createRollbackPoint(request, targetType, taskId);
        return GenerationArtifact.of("rollback_point", "Orchestrator", "生成前回滚点", rollbackPoint.toPayload());
    }

    RollbackPoint createRollbackPoint(GenerationOrchestrationRequest request,
                                      CodeGenTypeEnum targetType,
                                      String taskId) {
        App app = request == null ? null : request.app();
        Long appId = app == null ? null : app.getId();
        CodeGenTypeEnum sourceType = resolveSourceType(request, app);
        String sourceTypeValue = sourceType == null ? "" : sourceType.getValue();
        String targetTypeValue = targetType == null ? "" : targetType.getValue();
        if (appId == null || appId <= 0) {
            return RollbackPoint.skipped(appId, taskId, "", sourceTypeValue, targetTypeValue, "invalid_app_id");
        }
        if (request == null || !request.hasGeneratedCode()) {
            return RollbackPoint.skipped(appId, taskId, "", sourceTypeValue, targetTypeValue, "no_existing_generated_code");
        }
        if (sourceType == null) {
            return RollbackPoint.skipped(appId, taskId, "", "", targetTypeValue, "unknown_source_type");
        }
        Path projectPath = resolveProjectPath(appId, sourceType);
        if (!Files.isDirectory(projectPath)) {
            return RollbackPoint.skipped(
                    appId,
                    taskId,
                    projectPath.toString(),
                    sourceTypeValue,
                    targetTypeValue,
                    "project_directory_missing"
            );
        }
        try {
            Path snapshotRoot = codeSnapshotRoot.resolve(String.valueOf(appId))
                    .toAbsolutePath()
                    .normalize();
            Files.createDirectories(snapshotRoot);
            String snapshotName = buildSnapshotName(taskId);
            Path snapshotPath = snapshotRoot.resolve(snapshotName).normalize();
            ensureChildOf(snapshotRoot, snapshotPath);
            copyProject(projectPath, snapshotPath);
            int fileCount = listProjectFiles(snapshotPath).size();
            return RollbackPoint.created(
                    appId,
                    taskId,
                    snapshotName,
                    snapshotPath.toString(),
                    projectPath.toString(),
                    sourceTypeValue,
                    targetTypeValue,
                    fileCount
            );
        } catch (Exception e) {
            log.warn("创建生成前回滚点失败，appId: {}, taskId: {}", appId, taskId, e);
            return RollbackPoint.skipped(
                    appId,
                    taskId,
                    projectPath.toString(),
                    sourceTypeValue,
                    targetTypeValue,
                    "snapshot_create_failed:" + e.getMessage()
            );
        }
    }

    private CodeGenTypeEnum resolveSourceType(GenerationOrchestrationRequest request, App app) {
        if (request != null && request.currentType() != null) {
            return request.currentType();
        }
        return app == null ? null : CodeGenTypeEnum.getEnumByValue(app.getCodeGenType());
    }

    private Path resolveProjectPath(Long appId, CodeGenTypeEnum sourceType) {
        String projectDirName = sourceType.getValue() + "_" + appId;
        return codeOutputRoot.resolve(projectDirName)
                .toAbsolutePath()
                .normalize();
    }

    private String buildSnapshotName(String taskId) {
        String normalizedTaskId = taskId == null ? "unknown" : taskId.replaceAll("[^a-zA-Z0-9_-]", "_");
        return "pre_generation_" + normalizedTaskId + "_" + LocalDateTime.now().format(SNAPSHOT_TIME_FORMATTER);
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
                Path targetPath = targetRoot.resolve(relative);
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
}
