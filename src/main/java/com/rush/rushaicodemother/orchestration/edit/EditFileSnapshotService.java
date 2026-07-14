package com.rush.rushaicodemother.orchestration.edit;

import com.rush.rushaicodemother.infrastructure.diagnostic.LogExceptionSanitizer;
import com.rush.rushaicodemother.config.PatchExecutionProperties;
import com.rush.rushaicodemother.orchestration.patch.PatchOperation;
import com.rush.rushaicodemother.orchestration.patch.PatchWorkspaceException;
import com.rush.rushaicodemother.orchestration.patch.PatchWorkspaceFileService;
import com.rush.rushaicodemother.orchestration.patch.PatchWorkspaceTarget;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Captures a bounded edit-session snapshot through the hardened patch workspace boundary.
 * The snapshot spans all repair rounds so a failed runtime repair can restore the original state.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EditFileSnapshotService {

    private final PatchWorkspaceFileService workspaceFileService;
    private final PatchExecutionProperties properties;

    public EditFileSnapshot capture(Path projectRoot,
                                    List<PatchOperation> patchOperations) throws PatchWorkspaceException {
        Path realRoot = workspaceFileService.resolveProjectRoot(projectRoot);
        EditFileSnapshot snapshot = new EditFileSnapshot(realRoot);
        captureMissing(snapshot, patchOperations);
        return snapshot;
    }

    public void captureMissing(EditFileSnapshot snapshot,
                               List<PatchOperation> patchOperations) throws PatchWorkspaceException {
        if (snapshot == null || patchOperations == null || patchOperations.isEmpty()) {
            return;
        }
        synchronized (snapshot) {
            for (PatchOperation operation : patchOperations) {
                if (operation == null) {
                    continue;
                }
                PatchWorkspaceTarget target = workspaceFileService.resolve(
                        snapshot.projectRoot(), operation.relativePath());
                if (snapshot.contains(target.relativePath())) {
                    continue;
                }
                captureTarget(snapshot, target);
            }
        }
    }

    public RestoreResult restore(EditFileSnapshot snapshot) {
        if (snapshot == null || snapshot.projectRoot() == null || snapshot.isEmpty()) {
            return RestoreResult.skipped("snapshot_empty");
        }
        List<Map.Entry<String, FileSnapshot>> states = snapshot.states();
        List<String> restoredFiles = new ArrayList<>();
        List<String> failedFiles = new ArrayList<>();
        for (int index = states.size() - 1; index >= 0; index--) {
            Map.Entry<String, FileSnapshot> entry = states.get(index);
            String relativePath = entry.getKey();
            try {
                restoreTarget(snapshot.projectRoot(), relativePath, entry.getValue());
                restoredFiles.add(relativePath);
            } catch (Exception exception) {
                log.warn("Failed to restore edit snapshot file: {}", relativePath, LogExceptionSanitizer.sanitize(exception));
                failedFiles.add(relativePath + ":restore_failed");
            }
        }
        Collections.reverse(restoredFiles);
        Collections.reverse(failedFiles);
        if (!failedFiles.isEmpty()) {
            return new RestoreResult("failed", restoredFiles, failedFiles);
        }
        return new RestoreResult("restored", restoredFiles, List.of());
    }

    private void captureTarget(EditFileSnapshot snapshot,
                               PatchWorkspaceTarget target) throws PatchWorkspaceException {
        boolean existed = workspaceFileService.exists(target);
        String content = "";
        long contentBytes = 0;
        if (existed) {
            if (!workspaceFileService.isRegularFile(target)) {
                throw new PatchWorkspaceException("rollback_target_not_regular_file");
            }
            try {
                content = workspaceFileService.readUtf8(target);
            } catch (PatchWorkspaceException exception) {
                throw exception;
            } catch (Exception exception) {
                throw new PatchWorkspaceException("rollback_snapshot_capture_failed", exception);
            }
            contentBytes = content.getBytes(StandardCharsets.UTF_8).length;
            if (contentBytes > properties.getMaxRollbackSnapshotBytes() - snapshot.capturedBytes()) {
                throw new PatchWorkspaceException("rollback_snapshot_limit_exceeded");
            }
        }
        snapshot.add(target.relativePath(), new FileSnapshot(existed, content), contentBytes);
    }

    private void restoreTarget(Path projectRoot,
                               String relativePath,
                               FileSnapshot fileSnapshot) throws Exception {
        PatchWorkspaceTarget target = workspaceFileService.resolve(projectRoot, relativePath);
        boolean currentExists = workspaceFileService.exists(target);
        if (fileSnapshot.existed()) {
            if (currentExists) {
                workspaceFileService.overwriteUtf8(target, fileSnapshot.content());
            } else {
                workspaceFileService.writeNewUtf8(target, fileSnapshot.content());
            }
            return;
        }
        if (currentExists) {
            workspaceFileService.delete(target);
        }
    }

    public static final class EditFileSnapshot {

        private final Path projectRoot;
        private final Map<String, FileSnapshot> files = new LinkedHashMap<>();
        private long capturedBytes;

        private EditFileSnapshot(Path projectRoot) {
            this.projectRoot = projectRoot;
        }

        public Path projectRoot() {
            return projectRoot;
        }

        public Map<String, FileSnapshot> files() {
            synchronized (this) {
                return Collections.unmodifiableMap(new LinkedHashMap<>(files));
            }
        }

        public long capturedBytes() {
            return capturedBytes;
        }

        private boolean contains(String relativePath) {
            return files.containsKey(relativePath);
        }

        private boolean isEmpty() {
            synchronized (this) {
                return files.isEmpty();
            }
        }

        private void add(String relativePath, FileSnapshot snapshot, long contentBytes) {
            files.put(relativePath, snapshot);
            capturedBytes += contentBytes;
        }

        private List<Map.Entry<String, FileSnapshot>> states() {
            synchronized (this) {
                return new ArrayList<>(files.entrySet());
            }
        }
    }

    public record FileSnapshot(boolean existed, String content) {
    }

    public record RestoreResult(
            String status,
            List<String> restoredFiles,
            List<String> failedFiles
    ) {
        public RestoreResult {
            restoredFiles = restoredFiles == null ? List.of() : List.copyOf(restoredFiles);
            failedFiles = failedFiles == null ? List.of() : List.copyOf(failedFiles);
        }

        public static RestoreResult skipped(String reason) {
            return new RestoreResult("skipped:" + reason, List.of(), List.of());
        }

        public boolean restored() {
            return "restored".equals(status);
        }
    }
}
