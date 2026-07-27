package com.rush.rushaicodemother.orchestration.patch;

import com.rush.rushaicodemother.config.PatchExecutionProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 捕获有界文件快照并在补丁批次失败时恢复它。 */
@Component
@RequiredArgsConstructor
public class PatchBatchRollbackService {

    private final PatchWorkspaceFileService workspaceFileService;
    private final PatchExecutionProperties properties;

    public PatchRollbackSnapshot capture(List<PatchWorkspaceTarget> targets) throws IOException {
        Map<String, PatchRollbackSnapshot.FileState> states = new LinkedHashMap<>();
        long capturedBytes = 0;
        for (PatchWorkspaceTarget target : targets) {
            if (target == null || states.containsKey(target.relativePath())) {
                continue;
            }
            boolean existed = workspaceFileService.exists(target);
            String content = "";
            if (existed) {
                if (!workspaceFileService.isRegularFile(target)) {
                    throw new PatchWorkspaceException("rollback_target_not_regular_file");
                }
                content = workspaceFileService.readUtf8(target);
                capturedBytes += content.getBytes(StandardCharsets.UTF_8).length;
                if (capturedBytes > properties.getMaxRollbackSnapshotBytes()) {
                    throw new PatchWorkspaceException("rollback_snapshot_limit_exceeded");
                }
            }
            states.put(target.relativePath(), new PatchRollbackSnapshot.FileState(target, existed, content));
        }
        return new PatchRollbackSnapshot(new ArrayList<>(states.values()));
    }

    public void restore(PatchRollbackSnapshot snapshot) throws IOException {
        if (snapshot == null || snapshot.files().isEmpty()) {
            return;
        }
        List<IOException> failures = new ArrayList<>();
        List<PatchRollbackSnapshot.FileState> files = snapshot.files();
        for (int index = files.size() - 1; index >= 0; index--) {
            PatchRollbackSnapshot.FileState state = files.get(index);
            try {
                restoreFile(state);
            } catch (IOException exception) {
                failures.add(exception);
            }
        }
        if (!failures.isEmpty()) {
            IOException rollbackFailure = new IOException("patch_batch_rollback_failed");
            failures.forEach(rollbackFailure::addSuppressed);
            throw rollbackFailure;
        }
    }

    private void restoreFile(PatchRollbackSnapshot.FileState state) throws IOException {
        boolean currentExists = workspaceFileService.exists(state.target());
        if (state.existed()) {
            if (currentExists) {
                workspaceFileService.overwriteUtf8(state.target(), state.content());
            } else {
                workspaceFileService.writeNewUtf8(state.target(), state.content());
            }
            return;
        }
        if (currentExists) {
            workspaceFileService.delete(state.target());
        }
    }
}
