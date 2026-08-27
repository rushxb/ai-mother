package com.rush.rushaicodemother.orchestration.artifact;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 失败后本地快照恢复结果契约。
 *
 * <p>v2 记录实际消费的不可变快照身份；v1 仅供历史数据解析，不能作为重放时
 * “已经安全恢复”的判定依据。</p>
 */
public record RollbackRestore(
        String schemaVersion,
        String provider,
        String status,
        Long appId,
        String taskId,
        String rollbackStrategy,
        String snapshotName,
        String snapshotId,
        String manifestSha256,
        String scope,
        long executionEpoch,
        String snapshotPath,
        String projectPath,
        String backupPath,
        int restoredFileCount,
        String reason,
        LocalDateTime restoredAt
) {

    public static final String KEY = "rollback_restore";
    public static final String CURRENT_SCHEMA_VERSION = "v2";
    public static final String LEGACY_SCHEMA_VERSION = "v1";

    private static final String ROLE = "Orchestrator";
    private static final String TITLE = "Rollback restore";
    private static final String PROVIDER = "local_snapshot";
    private static final String STATUS_RESTORED = "restored";
    private static final String STATUS_SKIPPED = "skipped";
    private static final String STATUS_FAILED = "failed";
    private static final Pattern SHA256_HEX = Pattern.compile("[0-9a-f]{64}");
    private static final Set<String> SUPPORTED_SCHEMAS = Set.of(CURRENT_SCHEMA_VERSION, LEGACY_SCHEMA_VERSION);
    private static final Set<String> SUPPORTED_STATUSES = Set.of(
            STATUS_RESTORED,
            STATUS_SKIPPED,
            STATUS_FAILED
    );

    /** 创建实例并校验 schema、身份与状态不变量。 */
    public RollbackRestore {
        schemaVersion = requireSupportedSchema(schemaVersion);
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
        snapshotName = requireString(snapshotName, "snapshotName");
        snapshotId = requireString(snapshotId, "snapshotId");
        manifestSha256 = requireString(manifestSha256, "manifestSha256").toLowerCase();
        scope = requireString(scope, "scope");
        snapshotPath = requireString(snapshotPath, "snapshotPath");
        projectPath = requireString(projectPath, "projectPath");
        backupPath = requireString(backupPath, "backupPath");
        reason = requireString(reason, "reason");
        if (restoredFileCount < 0) {
            throw invalidField("restoredFileCount", "不能为负数");
        }
        restoredAt = Objects.requireNonNull(restoredAt, "回滚恢复制品字段 restoredAt 不能为空");
        validateState(
                schemaVersion,
                status,
                snapshotName,
                snapshotId,
                manifestSha256,
                scope,
                executionEpoch,
                snapshotPath,
                projectPath,
                backupPath,
                restoredFileCount,
                reason
        );
    }

    /** 创建已完成且绑定精确快照身份的 v2 恢复事实。 */
    public static RollbackRestore restored(Long appId,
                                           String taskId,
                                           String rollbackStrategy,
                                           String snapshotName,
                                           String snapshotId,
                                           String manifestSha256,
                                           String scope,
                                           long executionEpoch,
                                           String snapshotPath,
                                           String projectPath,
                                           String backupPath,
                                           int restoredFileCount) {
        return new RollbackRestore(
                CURRENT_SCHEMA_VERSION,
                PROVIDER,
                STATUS_RESTORED,
                appId,
                taskId,
                rollbackStrategy,
                snapshotName,
                snapshotId,
                manifestSha256,
                scope,
                executionEpoch,
                snapshotPath,
                projectPath,
                backupPath,
                restoredFileCount,
                "",
                LocalDateTime.now()
        );
    }

    /** 创建不携带快照成功事实的 v2 跳过结果。 */
    public static RollbackRestore skipped(Long appId,
                                          String taskId,
                                          String rollbackStrategy,
                                          String snapshotPath,
                                          String projectPath,
                                          String reason) {
        return new RollbackRestore(
                CURRENT_SCHEMA_VERSION,
                PROVIDER,
                STATUS_SKIPPED,
                appId,
                taskId,
                rollbackStrategy,
                "",
                "",
                "",
                "",
                0,
                snapshotPath,
                projectPath,
                "",
                0,
                reason,
                LocalDateTime.now()
        );
    }

    /** 创建已绑定待恢复快照身份的 v2 失败结果。 */
    public static RollbackRestore failed(Long appId,
                                         String taskId,
                                         String rollbackStrategy,
                                         String snapshotName,
                                         String snapshotId,
                                         String manifestSha256,
                                         String scope,
                                         long executionEpoch,
                                         String snapshotPath,
                                         String projectPath,
                                         String backupPath,
                                         String reason) {
        return new RollbackRestore(
                CURRENT_SCHEMA_VERSION,
                PROVIDER,
                STATUS_FAILED,
                appId,
                taskId,
                rollbackStrategy,
                snapshotName,
                snapshotId,
                manifestSha256,
                scope,
                executionEpoch,
                snapshotPath,
                projectPath,
                backupPath,
                0,
                reason,
                LocalDateTime.now()
        );
    }

    /** 从持久化制品恢复并校验当前应用与任务身份。 */
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
        String schemaVersion = requireSupportedSchema(requireTextValue(payload.get("schemaVersion"), "schemaVersion"));
        boolean currentSchema = CURRENT_SCHEMA_VERSION.equals(schemaVersion);
        RollbackRestore restored = new RollbackRestore(
                schemaVersion,
                requireTextValue(payload.get("provider"), "provider"),
                requireTextValue(payload.get("status"), "status"),
                requireLong(payload.get("appId"), "appId"),
                requireTextValue(payload.get("taskId"), "taskId"),
                requireTextValue(payload.get("rollbackStrategy"), "rollbackStrategy"),
                currentSchema ? requireStringValue(payload.get("snapshotName"), "snapshotName") : "",
                currentSchema ? requireStringValue(payload.get("snapshotId"), "snapshotId") : "",
                currentSchema ? requireStringValue(payload.get("manifestSha256"), "manifestSha256") : "",
                currentSchema ? requireStringValue(payload.get("scope"), "scope") : "",
                currentSchema ? requireNonNegativeLong(payload.get("executionEpoch"), "executionEpoch") : 0,
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

    /** 将当前对象转换为稳定载荷。 */
    public Map<String, Object> toPayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", schemaVersion);
        payload.put("provider", provider);
        payload.put("status", status);
        payload.put("appId", appId);
        payload.put("taskId", taskId);
        payload.put("rollbackStrategy", rollbackStrategy);
        if (CURRENT_SCHEMA_VERSION.equals(schemaVersion)) {
            payload.put("snapshotName", snapshotName);
            payload.put("snapshotId", snapshotId);
            payload.put("manifestSha256", manifestSha256);
            payload.put("scope", scope);
            payload.put("executionEpoch", executionEpoch);
        }
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

    /** 只有当前 schema 的结果可抑制后续恢复重放。 */
    public boolean trustedForReplay() {
        return CURRENT_SCHEMA_VERSION.equals(schemaVersion);
    }

    private static void validateState(
            String schemaVersion,
            String status,
            String snapshotName,
            String snapshotId,
            String manifestSha256,
            String scope,
            long executionEpoch,
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
            validateSnapshotIdentity(
                    schemaVersion,
                    snapshotName,
                    snapshotId,
                    manifestSha256,
                    scope,
                    executionEpoch
            );
            return;
        }
        if (restoredFileCount != 0) {
            throw invalidField("restoredFileCount", "未恢复成功时必须为 0");
        }
        requireText(reason, "reason");
        if (STATUS_SKIPPED.equals(status)) {
            if (!backupPath.isEmpty()) {
                throw invalidField("backupPath", "跳过恢复时必须为空");
            }
            if (!snapshotName.isEmpty()
                    || !snapshotId.isEmpty()
                    || !manifestSha256.isEmpty()
                    || !scope.isEmpty()
                    || executionEpoch != 0) {
                throw invalidField("snapshotId", "跳过恢复时不能携带成功快照身份");
            }
            return;
        }
        requireText(snapshotPath, "snapshotPath");
        requireText(projectPath, "projectPath");
        validateSnapshotIdentity(
                schemaVersion,
                snapshotName,
                snapshotId,
                manifestSha256,
                scope,
                executionEpoch
        );
    }

    private static void validateSnapshotIdentity(String schemaVersion,
                                                 String snapshotName,
                                                 String snapshotId,
                                                 String manifestSha256,
                                                 String scope,
                                                 long executionEpoch) {
        if (LEGACY_SCHEMA_VERSION.equals(schemaVersion)) {
            if (!snapshotName.isEmpty()
                    || !snapshotId.isEmpty()
                    || !manifestSha256.isEmpty()
                    || !scope.isEmpty()
                    || executionEpoch != 0) {
                throw invalidField("snapshotId", "v1 不能携带 v2 快照身份");
            }
            return;
        }
        requireText(snapshotName, "snapshotName");
        requireCanonicalUuid(snapshotId, "snapshotId");
        if (!SHA256_HEX.matcher(manifestSha256).matches()) {
            throw invalidField("manifestSha256", "必须为小写 SHA-256");
        }
        requireText(scope, "scope");
        if (executionEpoch <= 0) {
            throw invalidField("executionEpoch", "必须为正整数");
        }
    }

    private static String requireSupportedSchema(String schemaVersion) {
        String value = requireText(schemaVersion, "schemaVersion");
        if (!SUPPORTED_SCHEMAS.contains(value)) {
            throw invalidField("schemaVersion", "不受支持: " + value);
        }
        return value;
    }

    private static void requireCanonicalUuid(String value, String field) {
        try {
            if (!UUID.fromString(value).toString().equals(value)) {
                throw new IllegalArgumentException("not canonical");
            }
        } catch (RuntimeException exception) {
            throw invalidField(field, "必须为规范 UUID");
        }
    }

    private static Long requireLong(Object value, String field) {
        if (value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long) {
            return ((Number) value).longValue();
        }
        throw invalidField(field, "必须为整数");
    }

    private static long requireNonNegativeLong(Object value, String field) {
        long parsed = requireLong(value, field);
        if (parsed < 0) {
            throw invalidField(field, "必须为非负整数");
        }
        return parsed;
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
