package com.rush.rushaicodemother.orchestration.artifact;

import cn.hutool.core.util.StrUtil;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 生成前回滚点的强类型持久事实。
 *
 * <p>v2 使用 snapshotId、manifest 摘要、逻辑 scope 与执行纪元绑定不可变快照。
 * v1 只保留兼容解析能力，不能继续作为自动恢复或 Diff 的可信输入。</p>
 */
public record RollbackPoint(
        String schemaVersion,
        String provider,
        String status,
        Long appId,
        String taskId,
        String snapshotName,
        String snapshotId,
        String manifestSha256,
        String scope,
        long executionEpoch,
        String snapshotPath,
        String projectPath,
        String sourceType,
        String targetType,
        int fileCount,
        String reason,
        LocalDateTime createdAt
) {

    public static final String KEY = "rollback_point";
    public static final String CURRENT_SCHEMA_VERSION = "v2";
    public static final String LEGACY_SCHEMA_VERSION = "v1";

    private static final String PROVIDER = "local_snapshot";
    private static final String STATUS_CREATED = "created";
    private static final String STATUS_SKIPPED = "skipped";
    private static final String ROLE = "Orchestrator";
    private static final String TITLE = "生成前回滚点";
    private static final Pattern SHA256_HEX = Pattern.compile("[0-9a-f]{64}");
    private static final Set<String> SUPPORTED_SCHEMAS = Set.of(CURRENT_SCHEMA_VERSION, LEGACY_SCHEMA_VERSION);

    public RollbackPoint {
        schemaVersion = StrUtil.blankToDefault(schemaVersion, CURRENT_SCHEMA_VERSION);
        provider = StrUtil.blankToDefault(provider, PROVIDER);
        status = StrUtil.blankToDefault(status, STATUS_SKIPPED);
        snapshotName = StrUtil.blankToDefault(snapshotName, "");
        snapshotId = StrUtil.blankToDefault(snapshotId, "");
        manifestSha256 = StrUtil.blankToDefault(manifestSha256, "").toLowerCase();
        scope = StrUtil.blankToDefault(scope, "");
        snapshotPath = StrUtil.blankToDefault(snapshotPath, "");
        projectPath = StrUtil.blankToDefault(projectPath, "");
        sourceType = StrUtil.blankToDefault(sourceType, "");
        targetType = StrUtil.blankToDefault(targetType, "");
        reason = StrUtil.blankToDefault(reason, "");
        createdAt = createdAt == null ? LocalDateTime.now() : createdAt;
    }

    /** 创建绑定不可变快照身份的 v2 回滚点。 */
    public static RollbackPoint created(Long appId,
                                        String taskId,
                                        String snapshotName,
                                        String snapshotId,
                                        String manifestSha256,
                                        String scope,
                                        long executionEpoch,
                                        String snapshotPath,
                                        String projectPath,
                                        String sourceType,
                                        String targetType,
                                        int fileCount) {
        return new RollbackPoint(
                CURRENT_SCHEMA_VERSION,
                PROVIDER,
                STATUS_CREATED,
                appId,
                taskId,
                snapshotName,
                snapshotId,
                manifestSha256,
                scope,
                executionEpoch,
                snapshotPath,
                projectPath,
                sourceType,
                targetType,
                fileCount,
                "",
                LocalDateTime.now()
        );
    }

    /** 创建未执行复制但保留诊断原因的 v2 回滚点。 */
    public static RollbackPoint skipped(Long appId,
                                        String taskId,
                                        String projectPath,
                                        String sourceType,
                                        String targetType,
                                        String reason) {
        return new RollbackPoint(
                CURRENT_SCHEMA_VERSION,
                PROVIDER,
                STATUS_SKIPPED,
                appId,
                taskId,
                "",
                "",
                "",
                "",
                0,
                "",
                projectPath,
                sourceType,
                targetType,
                0,
                reason,
                LocalDateTime.now()
        );
    }

    /** 转换为稳定的持久化载荷。 */
    public Map<String, Object> toPayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", schemaVersion);
        payload.put("provider", provider);
        payload.put("status", status);
        payload.put("appId", appId);
        payload.put("taskId", taskId);
        payload.put("snapshotName", snapshotName);
        if (CURRENT_SCHEMA_VERSION.equals(schemaVersion)) {
            payload.put("snapshotId", snapshotId);
            payload.put("manifestSha256", manifestSha256);
            payload.put("scope", scope);
            payload.put("executionEpoch", executionEpoch);
        }
        payload.put("snapshotPath", snapshotPath);
        payload.put("projectPath", projectPath);
        payload.put("sourceType", sourceType);
        payload.put("targetType", targetType);
        payload.put("fileCount", fileCount);
        payload.put("reason", reason);
        payload.put("createdAt", createdAt.toString());
        return payload;
    }

    /** 从持久制品恢复回滚点，并校验其是否属于当前应用和任务。 */
    public static RollbackPoint fromArtifact(GenerationArtifact artifact,
                                             Long expectedAppId,
                                             String expectedTaskId) {
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
        String schemaVersion = requireSupportedSchema(payload.get("schemaVersion"));
        boolean currentSchema = CURRENT_SCHEMA_VERSION.equals(schemaVersion);
        RollbackPoint rollbackPoint = new RollbackPoint(
                schemaVersion,
                requireExactText(payload, "provider", PROVIDER),
                requireText(payload.get("status"), "status", false),
                requirePositiveLong(payload.get("appId"), "appId"),
                requireText(payload.get("taskId"), "taskId", false),
                requireText(payload.get("snapshotName"), "snapshotName", true),
                currentSchema ? requireText(payload.get("snapshotId"), "snapshotId", true) : "",
                currentSchema ? requireText(payload.get("manifestSha256"), "manifestSha256", true) : "",
                currentSchema ? requireText(payload.get("scope"), "scope", true) : "",
                currentSchema ? requireNonNegativeLong(payload.get("executionEpoch"), "executionEpoch") : 0,
                requireText(payload.get("snapshotPath"), "snapshotPath", true),
                requireText(payload.get("projectPath"), "projectPath", true),
                requireText(payload.get("sourceType"), "sourceType", true),
                requireText(payload.get("targetType"), "targetType", true),
                requireNonNegativeInteger(payload.get("fileCount"), "fileCount"),
                requireText(payload.get("reason"), "reason", true),
                requireCreatedAt(payload.get("createdAt"))
        );
        rollbackPoint.validateContext(expectedAppId, expectedTaskId);
        rollbackPoint.validateState();
        return rollbackPoint;
    }

    /** 转换为统一角色与标题的可持久制品。 */
    public GenerationArtifact toArtifact() {
        validateState();
        return GenerationArtifact.of(KEY, ROLE, TITLE, toPayload());
    }

    public boolean created() {
        return STATUS_CREATED.equals(status);
    }

    /** 只有 v2 created 制品可驱动恢复或 Diff。 */
    public boolean trustedForSnapshotConsumption() {
        return created() && CURRENT_SCHEMA_VERSION.equals(schemaVersion);
    }

    private void validateContext(Long expectedAppId, String expectedTaskId) {
        if (expectedAppId != null && !Objects.equals(expectedAppId, appId)) {
            throw invalidField("appId", "与当前任务上下文不一致");
        }
        if (StrUtil.isNotBlank(expectedTaskId) && !expectedTaskId.trim().equals(taskId)) {
            throw invalidField("taskId", "与当前任务上下文不一致");
        }
    }

    private void validateState() {
        if (!SUPPORTED_SCHEMAS.contains(schemaVersion)) {
            throw invalidField("schemaVersion", "不受支持: " + schemaVersion);
        }
        if (!PROVIDER.equals(provider)) {
            throw invalidField("provider", "不受支持: " + provider);
        }
        if (appId == null || appId <= 0) {
            throw invalidField("appId", "必须为正整数");
        }
        if (StrUtil.isBlank(taskId)) {
            throw invalidField("taskId", "不能为空");
        }
        if (fileCount < 0) {
            throw invalidField("fileCount", "必须为非负整数");
        }
        if (!STATUS_CREATED.equals(status) && !STATUS_SKIPPED.equals(status)) {
            throw invalidField("status", "不受支持: " + status);
        }
        if (created()) {
            validateCreatedState();
            return;
        }
        if (fileCount != 0) {
            throw invalidField("fileCount", "在 skipped 状态下必须为 0");
        }
        if (StrUtil.isNotBlank(snapshotName)
                || StrUtil.isNotBlank(snapshotId)
                || StrUtil.isNotBlank(manifestSha256)
                || StrUtil.isNotBlank(scope)
                || StrUtil.isNotBlank(snapshotPath)
                || executionEpoch != 0) {
            throw invalidField("snapshotId", "在 skipped 状态下不能携带快照事实");
        }
        if (StrUtil.isBlank(reason)) {
            throw invalidField("reason", "在 skipped 状态下不能为空");
        }
    }

    private void validateCreatedState() {
        requireCreatedText(snapshotName, "snapshotName");
        requireCreatedText(snapshotPath, "snapshotPath");
        requireCreatedText(projectPath, "projectPath");
        requireKnownCodeGenType(sourceType, "sourceType");
        requireKnownCodeGenType(targetType, "targetType");
        if (StrUtil.isNotBlank(reason)) {
            throw invalidField("reason", "在 created 状态下必须为空");
        }
        if (LEGACY_SCHEMA_VERSION.equals(schemaVersion)) {
            if (StrUtil.isNotBlank(snapshotId)
                    || StrUtil.isNotBlank(manifestSha256)
                    || StrUtil.isNotBlank(scope)
                    || executionEpoch != 0) {
                throw invalidField("snapshotId", "v1 不能携带 v2 快照身份");
            }
            return;
        }
        requireCanonicalUuid(snapshotId, "snapshotId");
        if (!SHA256_HEX.matcher(manifestSha256).matches()) {
            throw invalidField("manifestSha256", "必须为小写 SHA-256");
        }
        requireCreatedText(scope, "scope");
        if (executionEpoch <= 0) {
            throw invalidField("executionEpoch", "必须为正整数");
        }
    }

    private static String requireSupportedSchema(Object value) {
        String schema = requireText(value, "schemaVersion", false);
        if (!SUPPORTED_SCHEMAS.contains(schema)) {
            throw invalidField("schemaVersion", "不受支持: " + schema);
        }
        return schema;
    }

    private static void requireCanonicalUuid(String value, String fieldName) {
        try {
            if (!UUID.fromString(value).toString().equals(value)) {
                throw new IllegalArgumentException("not canonical");
            }
        } catch (RuntimeException exception) {
            throw invalidField(fieldName, "必须为规范 UUID");
        }
    }

    private static void requireCreatedText(String value, String fieldName) {
        if (StrUtil.isBlank(value)) {
            throw invalidField(fieldName, "在 created 状态下不能为空");
        }
    }

    private static void requireKnownCodeGenType(String value, String fieldName) {
        requireCreatedText(value, fieldName);
        if (CodeGenTypeEnum.getEnumByValue(value) == null) {
            throw invalidField(fieldName, "不是支持的项目类型: " + value);
        }
    }

    private static String requireExactText(Map<String, Object> payload,
                                           String fieldName,
                                           String expected) {
        String actual = requireText(payload.get(fieldName), fieldName, false);
        if (!expected.equals(actual)) {
            throw invalidField(fieldName, "不受支持: " + actual);
        }
        return actual;
    }

    private static String requireText(Object value, String fieldName, boolean allowBlank) {
        if (!(value instanceof String text)) {
            throw invalidField(fieldName, "必须为字符串");
        }
        String normalized = text.trim();
        if (!allowBlank && normalized.isBlank()) {
            throw invalidField(fieldName, "不能为空");
        }
        return normalized;
    }

    private static long requireLong(Object value, String fieldName) {
        if (!(value instanceof Number number)) {
            throw invalidField(fieldName, "必须为整数");
        }
        long parsed = number.longValue();
        if (number.doubleValue() != (double) parsed) {
            throw invalidField(fieldName, "必须为整数");
        }
        return parsed;
    }

    private static Long requirePositiveLong(Object value, String fieldName) {
        long parsed = requireLong(value, fieldName);
        if (parsed <= 0) {
            throw invalidField(fieldName, "必须为正整数");
        }
        return parsed;
    }

    private static long requireNonNegativeLong(Object value, String fieldName) {
        long parsed = requireLong(value, fieldName);
        if (parsed < 0) {
            throw invalidField(fieldName, "必须为非负整数");
        }
        return parsed;
    }

    private static int requireNonNegativeInteger(Object value, String fieldName) {
        long parsed = requireNonNegativeLong(value, fieldName);
        if (parsed > Integer.MAX_VALUE) {
            throw invalidField(fieldName, "必须为非负整数");
        }
        return (int) parsed;
    }

    private static LocalDateTime requireCreatedAt(Object value) {
        if (value instanceof LocalDateTime createdAt) {
            return createdAt;
        }
        String text = requireText(value, "createdAt", false);
        try {
            return LocalDateTime.parse(text);
        } catch (DateTimeParseException exception) {
            throw invalidField("createdAt", "不是有效时间");
        }
    }

    private static IllegalArgumentException invalidField(String fieldName, String reason) {
        return new IllegalArgumentException("回滚点制品字段 " + fieldName + reason);
    }
}
