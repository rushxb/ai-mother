package com.rush.rushaicodemother.orchestration.edit;

import java.util.List;

/**
 * 编辑状态的本地持久化快照。该数据仅用于连续改修时的文件召回，不是任务生命周期事实源。
 */
record EditStateSnapshot(
        int schemaVersion,
        List<EditRecord> recentEdits,
        List<RecentFile> recentFiles,
        List<ValidationRecord> recentValidations,
        long updatedAtEpochMillis
) {

    static final int CURRENT_SCHEMA_VERSION = 2;

    EditStateSnapshot {
        recentEdits = recentEdits == null ? List.of() : List.copyOf(recentEdits);
        recentFiles = recentFiles == null ? List.of() : List.copyOf(recentFiles);
        recentValidations = recentValidations == null ? List.of() : List.copyOf(recentValidations);
    }

    static EditStateSnapshot empty(long nowEpochMillis) {
        return new EditStateSnapshot(
                CURRENT_SCHEMA_VERSION,
                List.of(),
                List.of(),
                List.of(),
                nowEpochMillis
        );
    }

    record EditRecord(String taskId, boolean success, long timestampEpochMillis) {
    }

    record RecentFile(String filePath, long lastModifiedEpochMillis, boolean success) {
    }

    record ValidationRecord(String taskId, String status, long timestampEpochMillis) {
    }
}
