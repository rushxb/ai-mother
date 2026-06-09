package com.rush.rushaicodemother.orchestration.edit;

import cn.hutool.core.util.StrUtil;
import com.rush.rushaicodemother.orchestration.patch.PatchOperation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 轻量编辑文件级快照。
 * 用于运行时错误修复失败后的本次编辑回滚，避免失败补丁污染后续改修上下文。
 */
@Slf4j
@Service
public class EditFileSnapshotService {

    public EditFileSnapshot capture(Path projectRoot, List<PatchOperation> patchOperations) {
        Path normalizedRoot = normalizeRoot(projectRoot);
        Map<String, FileSnapshot> files = new LinkedHashMap<>();
        captureMissing(files, normalizedRoot, patchOperations);
        return new EditFileSnapshot(normalizedRoot, files);
    }

    public void captureMissing(EditFileSnapshot snapshot, List<PatchOperation> patchOperations) {
        if (snapshot == null) {
            return;
        }
        captureMissing(snapshot.files(), snapshot.projectRoot(), patchOperations);
    }

    public RestoreResult restore(EditFileSnapshot snapshot) {
        if (snapshot == null || snapshot.projectRoot() == null || snapshot.files().isEmpty()) {
            return RestoreResult.skipped("snapshot_empty");
        }
        List<String> restoredFiles = new ArrayList<>();
        List<String> failedFiles = new ArrayList<>();
        for (Map.Entry<String, FileSnapshot> entry : snapshot.files().entrySet()) {
            String relativePath = entry.getKey();
            FileSnapshot fileSnapshot = entry.getValue();
            Path targetPath = snapshot.projectRoot().resolve(relativePath).toAbsolutePath().normalize();
            if (!targetPath.startsWith(snapshot.projectRoot())) {
                failedFiles.add(relativePath + ":path_outside_project");
                continue;
            }
            try {
                if (fileSnapshot.existed()) {
                    Path parent = targetPath.getParent();
                    if (parent != null) {
                        Files.createDirectories(parent);
                    }
                    Files.writeString(targetPath, fileSnapshot.content(), StandardCharsets.UTF_8);
                } else if (Files.exists(targetPath)) {
                    Files.delete(targetPath);
                }
                restoredFiles.add(relativePath);
            } catch (IOException e) {
                log.warn("恢复轻量编辑文件快照失败: {}", relativePath, e);
                failedFiles.add(relativePath + ":" + e.getMessage());
            }
        }
        if (!failedFiles.isEmpty()) {
            return new RestoreResult("failed", restoredFiles, failedFiles);
        }
        return new RestoreResult("restored", restoredFiles, List.of());
    }

    private void captureMissing(Map<String, FileSnapshot> files,
                                Path normalizedRoot,
                                List<PatchOperation> patchOperations) {
        if (normalizedRoot == null || patchOperations == null || patchOperations.isEmpty()) {
            return;
        }
        for (PatchOperation operation : patchOperations) {
            String relativePath = normalizePath(operation == null ? "" : operation.relativePath());
            if (StrUtil.isBlank(relativePath) || files.containsKey(relativePath)) {
                continue;
            }
            Path targetPath = normalizedRoot.resolve(relativePath).toAbsolutePath().normalize();
            if (!targetPath.startsWith(normalizedRoot)) {
                continue;
            }
            try {
                if (Files.isRegularFile(targetPath)) {
                    files.put(relativePath, new FileSnapshot(true, Files.readString(targetPath, StandardCharsets.UTF_8)));
                } else {
                    files.put(relativePath, new FileSnapshot(false, ""));
                }
            } catch (IOException e) {
                log.debug("捕获轻量编辑文件快照失败: {}, error: {}", relativePath, e.getMessage());
            }
        }
    }

    private Path normalizeRoot(Path projectRoot) {
        return projectRoot == null ? null : projectRoot.toAbsolutePath().normalize();
    }

    private String normalizePath(String relativePath) {
        return StrUtil.blankToDefault(relativePath, "").replace('\\', '/');
    }

    public record EditFileSnapshot(
            Path projectRoot,
            Map<String, FileSnapshot> files
    ) {
    }

    public record FileSnapshot(
            boolean existed,
            String content
    ) {
    }

    public record RestoreResult(
            String status,
            List<String> restoredFiles,
            List<String> failedFiles
    ) {
        public static RestoreResult skipped(String reason) {
            return new RestoreResult("skipped:" + reason, List.of(), List.of());
        }

        public boolean restored() {
            return "restored".equals(status);
        }
    }
}
