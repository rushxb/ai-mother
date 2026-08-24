package com.rush.rushaicodemother.orchestration.artifact;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 失败后本地快照恢复结果契约。
 *
 * <p>该 module 集中拥有恢复结果的 schema、任务身份与状态不变量。持久检查点恢复时
 * 必须先通过本类型校验，不能仅凭同名 key 或 raw status 判断已经完成恢复。</p>
 */
public record RollbackRestore(
        String schemaVersion,
        String provider,
        String status,
        Long appId,
        String taskId,
        String rollbackStrategy,
        String snapshotPath,
        String projectPath,
        String backupPath,
        int restoredFileCount,
        String reason,
        LocalDateTime restoredAt
) {

    public static final String KEY = "rollback_restore";

    private static final String ROLE = "Orchestrator";
    private static final String TITLE = "Rollback restore";
    private static final String SCHEMA_VERSION = "v1";
    private static final String PROVIDER = "local_snapshot";
    private static final String STATUS_RESTORED = "restored";
    private static final String STATUS_SKIPPED = "skipped";
    private static final String STATUS_FAILED = "failed";
    private static final Set<String> SUPPORTED_STATUSES = Set.of(
            STATUS_RESTORED,
            STATUS_SKIPPED,
            STATUS_FAILED
    );

    /** 创建回滚恢复实例并校验规范 schema、身份与状态不变量。 */
    public RollbackRestore {
        schemaVersion = requireExactText(schemaVersion, SCHEMA_VERSION, "schemaVersion");
        provider = requireExactText(provider, PROVIDER, "provider");
        status = requireText(status, "status");
        if (!SUPPORTED_STATUSES.contains(status)) {
            throw invalidField("status", "不受支持: " + status);
        }
        if (appId == null || appId <= 0) {
            throw invalidField("appId", "应用标识必须为正整数");
        }
        taskId = requireText(taskId, "taskId");
        rollbackStrategy = requireText(rollbackStrategy, "rollbackStrategy");
        snapshotPath = requireString(snapshotPath, "snapshotPath");
        projectPath = requireString(projectPath, "projectPath");
        backupPath = requireString(backupPath, "backupPath");
        reason = requireString(reason, "reason");
        if (restoredFileCount < 0) {
            throw invalidField("restoredFileCount", "不能为负数");
        }
        restoredAt = Objects.requireNonNull(restoredAt, "回滚恢复制品字段 restoredAt 不能为空");
        validateState(status, snapshotPath, projectPath, backupPath, restoredFileCount, reason);
    }

    /**
 * 返回{@code restored}。
 *
 * @param appId 应用编号
 * @param taskId 任务编号
 * @param rollbackStrategy {@code rollbackStrategy} 对应的调用参数
 * @param snapshotPath 快照路径
 * @param projectPath 项目路径
 * @param backupPath {@code backupPath} 对应的调用参数
 * @param restoredFileCount {@code restoredFileCount} 对应的调用参数
 * @return 回滚恢复
 */
    public static RollbackRestore restored(Long appId,
                                           String taskId,
                                           String rollbackStrategy,
                                           String snapshotPath,
                                           String projectPath,
                                           String backupPath,
                                           int restoredFileCount) {
        return new RollbackRestore(
                SCHEMA_VERSION,
                PROVIDER,
                STATUS_RESTORED,
                appId,
                taskId,
                rollbackStrategy,
                snapshotPath,
                projectPath,
                backupPath,
                restoredFileCount,
                "",
                LocalDateTime.now()
        );
    }

    /**
 * 返回{@code skipped}。
 *
 * @param appId 应用编号
 * @param taskId 任务编号
 * @param rollbackStrategy {@code rollbackStrategy} 对应的调用参数
 * @param snapshotPath 快照路径
 * @param projectPath 项目路径
 * @param reason 原因
 * @return 回滚恢复
 */
    public static RollbackRestore skipped(Long appId,
                                          String taskId,
                                          String rollbackStrategy,
                                          String snapshotPath,
                                          String projectPath,
                                          String reason) {
        return new RollbackRestore(
                SCHEMA_VERSION,
                PROVIDER,
                STATUS_SKIPPED,
                appId,
                taskId,
                rollbackStrategy,
                snapshotPath,
                projectPath,
                "",
                0,
                reason,
                LocalDateTime.now()
        );
    }

    /**
 * 将{@code ed}标记为失败并记录原因。
 *
 * @param appId 应用编号
 * @param taskId 任务编号
 * @param rollbackStrategy {@code rollbackStrategy} 对应的调用参数
 * @param snapshotPath 快照路径
 * @param projectPath 项目路径
 * @param backupPath {@code backupPath} 对应的调用参数
 * @param reason 原因
 * @return {@code ed}
 */
    public static RollbackRestore failed(Long appId,
                                         String taskId,
                                         String rollbackStrategy,
                                         String snapshotPath,
                                         String projectPath,
                                         String backupPath,
                                         String reason) {
        return new RollbackRestore(
                SCHEMA_VERSION,
                PROVIDER,
                STATUS_FAILED,
                appId,
                taskId,
                rollbackStrategy,
                snapshotPath,
                projectPath,
                backupPath,
                0,
                reason,
                LocalDateTime.now()
        );
    }

    /**
     * 从持久化制品恢复并校验当前应用与任务身份。
     *
     * @param artifact 持久化制品
     * @param expectedAppId 期望应用；为 {@code null} 时只校验载荷本身
     * @param expectedTaskId 期望任务；为空时只校验载荷本身
     */
    public static RollbackRestore fromArtifact(
            GenerationArtifact artifact,
            Long expectedAppId,
            String expectedTaskId
    ) {
        if (artifact == null) {
            throw invalidField("artifact", "不能为空");
        }
        if (!KEY.equals(artifact.key())) {
            throw invalidField("key", "制品类型不匹配: " + artifact.key());
        }
        Map<String, Object> payload = artifact.payload();
        if (payload == null) {
            throw invalidField("payload", "不能为空");
        }
        RollbackRestore restored = new RollbackRestore(
                requireTextValue(payload.get("schemaVersion"), "schemaVersion"),
                requireTextValue(payload.get("provider"), "provider"),
                requireTextValue(payload.get("status"), "status"),
                requireLong(payload.get("appId"), "appId"),
                requireTextValue(payload.get("taskId"), "taskId"),
                requireTextValue(payload.get("rollbackStrategy"), "rollbackStrategy"),
                requireStringValue(payload.get("snapshotPath"), "snapshotPath"),
                requireStringValue(payload.get("projectPath"), "projectPath"),
                requireStringValue(payload.get("backupPath"), "backupPath"),
                requireInteger(payload.get("restoredFileCount"), "restoredFileCount"),
                requireStringValue(payload.get("reason"), "reason"),
                requireDateTime(payload.get("restoredAt"), "restoredAt")
        );
        if (expectedAppId != null && !expectedAppId.equals(restored.appId())) {
            throw invalidField(
                    "appId",
                    "应用标识不匹配，期望 " + expectedAppId + "，实际 " + restored.appId()
            );
        }
        if (expectedTaskId != null
                && !expectedTaskId.isBlank()
                && !expectedTaskId.equals(restored.taskId())) {
            throw invalidField(
                    "taskId",
                    "任务标识不匹配，期望 " + expectedTaskId + "，实际 " + restored.taskId()
            );
        }
        return restored;
    }

    /**
 * 将当前对象转换为载荷。
 *
 * @return 载荷集合
 */
    public Map<String, Object> toPayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", schemaVersion);
        payload.put("provider", provider);
        payload.put("status", status);
        payload.put("appId", appId);
        payload.put("taskId", taskId);
        payload.put("rollbackStrategy", rollbackStrategy);
        payload.put("snapshotPath", snapshotPath);
        payload.put("projectPath", projectPath);
        payload.put("backupPath", backupPath);
        payload.put("restoredFileCount", restoredFileCount);
        payload.put("reason", reason);
        payload.put("restoredAt", restoredAt.toString());
        return payload;
    }

    /** 转换为供任务检查点和失败事件共享的规范制品。 */
    public GenerationArtifact toArtifact() {
        return GenerationArtifact.of(KEY, ROLE, TITLE, toPayload());
    }

    private static void validateState(
            String status,
            String snapshotPath,
            String projectPath,
            String backupPath,
            int restoredFileCount,
            String reason
    ) {
        if (STATUS_RESTORED.equals(status)) {
            requireText(snapshotPath, "snapshotPath");
            requireText(projectPath, "projectPath");
            if (!reason.isEmpty()) {
                throw invalidField("reason", "恢复成功时必须为空");
            }
            return;
        }
        if (restoredFileCount != 0) {
            throw invalidField("restoredFileCount", "未恢复成功时必须为 0");
        }
        requireText(reason, "reason");
        if (STATUS_SKIPPED.equals(status) && !backupPath.isEmpty()) {
            throw invalidField("backupPath", "跳过恢复时必须为空");
        }
        if (STATUS_FAILED.equals(status)) {
            requireText(snapshotPath, "snapshotPath");
            requireText(projectPath, "projectPath");
        }
    }

    private static Long requireLong(Object value, String field) {
        if (value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long) {
            return ((Number) value).longValue();
        }
        throw invalidField(field, "必须为整数");
    }

    private static int requireInteger(Object value, String field) {
        long parsed = requireLong(value, field);
        if (parsed < Integer.MIN_VALUE || parsed > Integer.MAX_VALUE) {
            throw invalidField(field, "超出整数范围");
        }
        return (int) parsed;
    }

    private static LocalDateTime requireDateTime(Object value, String field) {
        if (!(value instanceof String text) || text.isBlank()) {
            throw invalidField(field, "必须为时间字符串");
        }
        try {
            return LocalDateTime.parse(text);
        } catch (DateTimeParseException exception) {
            throw invalidField(field, "时间格式不合法");
        }
    }

    private static String requireTextValue(Object value, String field) {
        if (!(value instanceof String text)) {
            throw invalidField(field, "必须为字符串");
        }
        return requireText(text, field);
    }

    private static String requireStringValue(Object value, String field) {
        if (!(value instanceof String text)) {
            throw invalidField(field, "必须为字符串");
        }
        return text;
    }

    private static String requireText(String value, String field) {
        String text = requireString(value, field);
        if (text.isBlank() || !text.equals(text.trim())) {
            throw invalidField(field, "不能为空且不能包含首尾空白");
        }
        return text;
    }

    private static String requireString(String value, String field) {
        if (value == null) {
            throw invalidField(field, "不能为空");
        }
        return value;
    }

    private static String requireExactText(String actual, String expected, String field) {
        String value = requireText(actual, field);
        if (!expected.equals(value)) {
            throw invalidField(field, "必须为 " + expected);
        }
        return value;
    }

    private static IllegalArgumentException invalidField(String field, String reason) {
        return new IllegalArgumentException("回滚恢复制品字段 " + field + " " + reason);
    }
}
